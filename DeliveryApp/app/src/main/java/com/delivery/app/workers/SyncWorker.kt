package com.delivery.app.workers

import android.content.Context
import androidx.work.*
import com.delivery.app.DeliveryApplication
import com.delivery.app.data.repository.SyncManager
import com.delivery.app.utils.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext as DeliveryApplication
            when (val result = app.syncManager.syncNewOrders()) {
                is SyncManager.SyncResult.Success -> {
                    if (result.count > 0) {
                        NotificationHelper.notifyNewOrders(context, result.count)
                    }
                    Result.success()
                }
                is SyncManager.SyncResult.Error -> {
                    if (runAttemptCount < 3) Result.retry() else Result.success()
                }
            }
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "sync_orders"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
