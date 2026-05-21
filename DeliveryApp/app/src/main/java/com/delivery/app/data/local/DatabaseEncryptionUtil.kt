package com.delivery.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64

object DatabaseEncryptionUtil {
    private const val PREFS_NAME = "db_encryption_prefs"
    private const val KEY_PASSPHRASE = "db_passphrase"
    private const val KEY_MIGRATED = "db_migrated"

    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = getPrefs(context)
        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) {
            return Base64.getDecoder().decode(existing)
        }
        val newKey = ByteArray(32)
        SecureRandom().nextBytes(newKey)
        prefs.edit().putString(KEY_PASSPHRASE, Base64.getEncoder().encodeToString(newKey)).apply()
        return newKey
    }

    fun isMigratedToEncryption(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MIGRATED, false)
    }

    fun markMigrated(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
