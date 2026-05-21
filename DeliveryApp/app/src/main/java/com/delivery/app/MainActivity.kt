package com.delivery.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.delivery.app.data.SetupConfig
import com.delivery.app.databinding.ActivityMainBinding

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
        // منع الفتح من تطبيقات خارجية (إلا إذا كان من Launcher)
        if (intent.action != Intent.ACTION_MAIN) {
            finish()
            super.onCreate(savedInstanceState)
            return
        }

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

        // التحقق من PIN قبل عرض واجهة المستخدم
        if (checkPinLock()) {
            setupMainUi()
        }
    }

    private fun setupMainUi() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)
    }

    private fun showSetupKeyPrompt(prefs: SharedPreferences) {
        val input = EditText(this).apply {
            hint = "رمز التفعيل"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("رمز التفعيل")
            .setMessage("أدخل رمز التفعيل للمتابعة")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("تفعيل") { _, _ ->
                val key = input.text.toString().trim()
                if (SetupConfig.verifySetupKey(key)) {
                    prefs.edit().putBoolean("activated", true).apply()
                    recreate()
                } else {
                    Toast.makeText(this, "رمز التفعيل خطأ", Toast.LENGTH_SHORT).show()
                    showSetupKeyPrompt(prefs)
                }
            }
            .show()
    }

    private fun checkPinLock(): Boolean {
        val securePrefs = getPinPrefs()
        if (!securePrefs.getBoolean("pin_enabled", false)) return true
        val savedPin = securePrefs.getString("pin", "") ?: return true
        if (savedPin.isEmpty()) return true
        pinAttempts = securePrefs.getInt("pin_attempts", 0)
        if (pinAttempts >= 3) {
            Toast.makeText(this, "تم قفل التطبيق — أعد التثبيت", Toast.LENGTH_LONG).show()
            finishAffinity()
            return false
        }
        showPinDialog(savedPin.toCharArray(), securePrefs)
        return false
    }

    private fun showPinDialog(savedPinChars: CharArray, securePrefs: SharedPreferences) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            hint = "أدخل الرقم السري"
        }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("التطبيق مقفل 🔒")
            .setMessage("أدخل الرقم السري للمتابعة")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("دخول") { dialog, _ ->
                val enteredText = input.text?.toString()?.trim() ?: ""
                val enteredChars = enteredText.toCharArray()

                val match = savedPinChars contentEquals enteredChars

                // مسح الذاكرة
                enteredChars.fill('\u0000')

                if (match) {
                    savedPinChars.fill('\u0000')
                    securePrefs.edit().putInt("pin_attempts", 0).apply()
                    dialog.dismiss()
                    setupMainUi()
                } else {
                    pinAttempts++
                    securePrefs.edit().putInt("pin_attempts", pinAttempts).apply()
                    if (pinAttempts >= 3) {
                        savedPinChars.fill('\u0000')
                        Toast.makeText(this, "محاولات كثيرة خاطئة — تم قفل التطبيق", Toast.LENGTH_LONG).show()
                        finishAffinity()
                    } else {
                        Toast.makeText(this, "رقم سري خاطئ (محاولة $pinAttempts/3)", Toast.LENGTH_SHORT).show()
                        input.text?.clear()
                        showPinDialog(savedPinChars, securePrefs)
                    }
                }
            }
            .setOnCancelListener {
                savedPinChars.fill('\u0000')
                finishAffinity()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
