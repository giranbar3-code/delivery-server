package com.delivery.app

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.delivery.app.data.OfficeManager
import com.delivery.app.data.SetupConfig
import com.delivery.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var pinAttempts = 0

    private fun getPinPrefs(): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            "delivery_pin_prefs",
            masterKey,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getRemainingLockoutMillis(securePrefs: SharedPreferences): Long {
        val lockedUntil = securePrefs.getLong("locked_until", 0L)
        if (lockedUntil == 0L) return 0L
        val remaining = lockedUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    private fun applyPinLockout(securePrefs: SharedPreferences) {
        val totalAttempts = securePrefs.getInt("total_pin_attempts", 0) + 1
        securePrefs.edit().putInt("total_pin_attempts", totalAttempts).apply()
        val lockoutDuration = when {
            totalAttempts >= 9 -> 30 * 60 * 1000L  // 30 دقيقة
            totalAttempts >= 6 -> 5 * 60 * 1000L   // 5 دقائق
            totalAttempts >= 3 -> 30 * 1000L        // 30 ثانية
            else -> 0L
        }
        if (lockoutDuration > 0) {
            val lockedUntil = System.currentTimeMillis() + lockoutDuration
            securePrefs.edit().putLong("locked_until", lockedUntil).apply()
        }
    }

    private fun resetPinAttempts(securePrefs: SharedPreferences) {
        securePrefs.edit()
            .putInt("pin_attempts", 0)
            .putInt("total_pin_attempts", 0)
            .putLong("locked_until", 0)
            .apply()
    }

    private fun getActivationPrefs(): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            "delivery_activation_prefs",
            masterKey,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val isDark = settingsPrefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)

        val activationPrefs = getActivationPrefs()

        // ترحيل من SharedPreferences القديمة للمستخدمين الحاليين
        val oldActivated = settingsPrefs.getBoolean("activated", false)
        if (oldActivated && !activationPrefs.getBoolean("activated", false)) {
            activationPrefs.edit().putBoolean("activated", true).apply()
            settingsPrefs.edit().remove("activated").apply()
        }

        if (!activationPrefs.getBoolean("activated", false)) {
            showSetupKeyPrompt(activationPrefs)
            return
        }

        // التحقق من إعداد المكتب (مرة واحدة بعد التفعيل)
        if (!activationPrefs.getBoolean("office_setup_complete", false)) {
            showOfficeSetupDialog(activationPrefs)
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)

        checkPinLock()
    }

    private fun showSetupKeyPrompt(prefs: SharedPreferences) {
        val input = EditText(this).apply {
            hint = "رمز التفعيل"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("رمز التفعيل")
            .setMessage("أدخل رمز التفعيل للمتابعة")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("تفعيل", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this, "الرجاء إدخال رمز التفعيل", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "جارٍ التحقق..."

                lifecycleScope.launch {
                    val serverResult = SetupConfig.verifyWithServer(this@MainActivity, key)
                    val isValid = when {
                        serverResult == true -> true
                        serverResult == null -> SetupConfig.verifyLocally(key)
                        else -> false
                    }

                    if (isValid) {
                        prefs.edit().putBoolean("activated", true).apply()
                        dialog.dismiss()
                        recreate()
                    } else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "تفعيل"
                        Toast.makeText(this@MainActivity, "رمز التفعيل خطأ", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showOfficeSetupDialog(activationPrefs: SharedPreferences) {
        val input = EditText(this).apply {
            hint = "رقم المكتب (0 للمكتب الرئيسي)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("إعداد المكتب")
            .setMessage("أدخل رقم المكتب الخاص بك.\nيمكنك ترك 0 للمكتب الرئيسي.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("حفظ") { _, _ ->
                val id = input.text.toString().trim().toLongOrNull() ?: 0L
                OfficeManager.switchOffice(this@MainActivity, id)
                activationPrefs.edit().putBoolean("office_setup_complete", true).apply()
                recreate()
            }
            .show()
    }

    private fun checkPinLock() {
        val securePrefs = getPinPrefs()
        if (!securePrefs.getBoolean("pin_enabled", false)) return
        val savedPin = securePrefs.getString("pin", "") ?: return
        if (savedPin.isEmpty()) return

        // التحقق من الحظر الزمني
        val remaining = getRemainingLockoutMillis(securePrefs)
        if (remaining > 0) {
            val seconds = (remaining / 1000) + 1
            Toast.makeText(this, "التطبيق مقفل مؤقتاً — حاول بعد $seconds ثانية", Toast.LENGTH_LONG).show()
            // إعادة المحاولة بعد انتهاء مدة الحظر
            android.os.Handler(mainLooper).postDelayed({
                if (!isFinishing) checkPinLock()
            }, remaining.coerceAtMost(30000L))
            return
        }

        pinAttempts = securePrefs.getInt("pin_attempts", 0)
        if (pinAttempts >= 3) {
            applyPinLockout(securePrefs)
            checkPinLock() // إعادة التحقق — سيكتشف الحظر
            return
        }
        showPinDialog(savedPin, securePrefs)
    }

    private fun showPinDialog(savedPin: String, securePrefs: SharedPreferences) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            setHint("أدخل الرقم السري")
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("التطبيق مقفل 🔒")
            .setMessage("أدخل الرقم السري للمتابعة")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("دخول", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = input.text.toString().trim()
                if (entered == savedPin) {
                    resetPinAttempts(securePrefs)
                    dialog.dismiss()
                } else {
                    pinAttempts++
                    securePrefs.edit().putInt("pin_attempts", pinAttempts).apply()
                    if (pinAttempts >= 3) {
                        applyPinLockout(securePrefs)
                        dialog.dismiss()
                        checkPinLock() // يعرض رسالة الحظر
                    } else {
                        input.text?.clear()
                        Toast.makeText(this, "رقم سري خاطئ (محاولة $pinAttempts/3)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()
    }
}
