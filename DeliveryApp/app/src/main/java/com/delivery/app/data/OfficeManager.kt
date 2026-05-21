package com.delivery.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object OfficeManager {
    private const val PREFS_NAME = "office_prefs"
    private const val KEY_CURRENT_OFFICE_ID = "current_office_id"

    private val _currentOfficeId = MutableLiveData(0L)
    val currentOfficeId: LiveData<Long> = _currentOfficeId

    private fun getPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun init(context: Context) {
        val id = getPrefs(context).getLong(KEY_CURRENT_OFFICE_ID, 0L)
        _currentOfficeId.value = id
    }

    fun switchOffice(context: Context, officeId: Long) {
        _currentOfficeId.value = officeId
        getPrefs(context).edit().putLong(KEY_CURRENT_OFFICE_ID, officeId).apply()
    }
}
