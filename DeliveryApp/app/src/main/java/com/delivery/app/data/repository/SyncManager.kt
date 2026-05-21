package com.delivery.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.delivery.app.data.OfficeManager
import com.delivery.app.data.local.OrderDao
import com.delivery.app.data.mapper.OrderMapper
import com.delivery.app.data.model.Order
import com.delivery.app.data.remote.RetrofitClient
import com.delivery.app.data.remote.SyncRequest
import com.delivery.app.utils.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncManager(
    private val context: Context,
    private val orderDao: OrderDao
) {
    private val api get() = RetrofitClient.getApi(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    private var lastSyncTime: String
        get() {
            val stored = prefs.getString("last_sync", "1970-01-01") ?: "1970-01-01"
            // إصلاح القيمة القديمة "0" التي تسبب خطأ في PostgreSQL
            return if (stored == "0" || stored.isEmpty()) "1970-01-01" else stored
        }
        set(value) = prefs.edit().putString("last_sync", value).apply()

    suspend fun syncNewOrders(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val currentOfficeId = OfficeManager.currentOfficeId.value ?: 0L
            val remoteOrders = api.getNewOrders(since = lastSyncTime, officeId = currentOfficeId)

            if (remoteOrders.isEmpty()) {
                return@withContext SyncResult.Success(0)
            }

            val ids = mutableListOf<Long>()
            var count = 0
            val lastOrderNumber = orderDao.getLastOrderNumber(currentOfficeId)

            for ((index, ro) in remoteOrders.withIndex()) {
                val localOrder = OrderMapper.remoteToLocal(ro, orderNumber = lastOrderNumber + index + 1, officeId = currentOfficeId)
                orderDao.insertOrder(Order.encryptPii(localOrder))
                ids.add(ro.id)
                count++
            }

            if (ids.isNotEmpty()) {
                api.markSynced(SyncRequest(ids))
                lastSyncTime = remoteOrders.mapNotNull { it.createdAt }.maxOrNull()
                    ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date())
                NotificationHelper.notifyNewOrders(context, count)
            }

            SyncResult.Success(count)
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "خطأ في الاتصال بالخادم")
        }
    }

    sealed class SyncResult {
        data class Success(val count: Int) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }
}
