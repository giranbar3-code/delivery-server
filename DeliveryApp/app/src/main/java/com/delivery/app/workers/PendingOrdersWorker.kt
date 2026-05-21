package com.delivery.app.workers

import android.content.Context
import androidx.work.*
import com.delivery.app.DeliveryApplication
import com.delivery.app.utils.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PendingOrdersWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = (context.applicationContext as DeliveryApplication).database
            val pendingCount = db.orderDao().getPendingOrdersCountSync()
            if (pendingCount > 0) {
                NotificationHelper.notifyPendingOrders(context, pendingCount)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "pending_orders_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PendingOrdersWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build()
                ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}