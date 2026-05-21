package com.delivery.app.data.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private const val DEFAULT_BASE_URL = "https://delivery-server-mmdt.onrender.com/"

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"

    private var customBaseUrl: String? = null
    private var appContext: Context? = null

    private fun getSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            "secure_prefs",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun readApiKey(context: Context): String {
        val prefs = getSecurePrefs(context)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun getBaseUrl(context: Context): String {
        return customBaseUrl ?: run {
            val prefs = getSecurePrefs(context)
            prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        }
    }

    fun setBaseUrl(context: Context, url: String) {
        val normalizedUrl = if (url.endsWith("/")) url else "$url/"
        customBaseUrl = normalizedUrl
        val prefs = getSecurePrefs(context)
        prefs.edit().putString(KEY_BASE_URL, normalizedUrl).apply()
        resetClient()
    }

    fun getApiKey(context: Context): String {
        return readApiKey(context)
    }

    fun setApiKey(context: Context, key: String) {
        val prefs = getSecurePrefs(context)
        prefs.edit().putString(KEY_API_KEY, key).apply()
        resetClient()
    }

    @Volatile
    private var apiInstance: OrderApi? = null

    private fun createClient(baseUrl: String, context: Context): OrderApi {
        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val apiKey = readApiKey(context)
            val requestWithAuth = if (apiKey.isNotEmpty()) {
                originalRequest.newBuilder()
                    .header("X-API-Key", apiKey)
                    .build()
            } else originalRequest
            chain.proceed(requestWithAuth)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OrderApi::class.java)
    }

    fun getApi(context: Context): OrderApi {
        return apiInstance ?: synchronized(this) {
            apiInstance ?: createClient(getBaseUrl(context), context).also { apiInstance = it }
        }
    }

    private fun resetClient() {
        apiInstance = null
    }
}
