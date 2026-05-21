package com.delivery.app

import android.content.SharedPreferences
import android.os.Bundle
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

    private fun checkPinLock() {
        val securePrefs = getPinPrefs()
        if (!securePrefs.getBoolean("pin_enabled", false)) return
        val savedPin = securePrefs.getString("pin", "") ?: return
        if (savedPin.isEmpty()) return
        pinAttempts = securePrefs.getInt("pin_attempts", 0)
        if (pinAttempts >= 3) {
            Toast.makeText(this, "تم قفل التطبيق — أعد التثبيت", Toast.LENGTH_LONG).show()
            finishAffinity()
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

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("التطبيق مقفل 🔒")
            .setMessage("أدخل الرقم السري للمتابعة")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("دخول") { dialog, _ ->
                val entered = input.text.toString().trim()
                if (entered == savedPin) {
                    securePrefs.edit().putInt("pin_attempts", 0).apply()
                    dialog.dismiss()
                } else {
                    pinAttempts++
                    securePrefs.edit().putInt("pin_attempts", pinAttempts).apply()
                    if (pinAttempts >= 3) {
                        Toast.makeText(this, "محاولات كثيرة خاطئة — تم قفل التطبيق", Toast.LENGTH_LONG).show()
                        finishAffinity()
                    } else {
                        Toast.makeText(this, "رقم سري خاطئ (محاولة $pinAttempts/3)", Toast.LENGTH_SHORT).show()
                        input.text?.clear()
                        showPinDialog(savedPin, securePrefs)
                    }
                }
            }
            .setCancelable(false)
            .show()
    }
}
