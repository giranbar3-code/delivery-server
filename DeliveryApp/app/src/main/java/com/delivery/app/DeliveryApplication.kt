package com.delivery.app

import android.app.Application
import android.util.Log
import com.delivery.app.data.local.DeliveryDatabase
import com.delivery.app.data.repository.CustomerRepository
import com.delivery.app.data.repository.DriverRepository
import com.delivery.app.data.repository.OrderRepository
import com.delivery.app.data.repository.OfficeRepository
import com.delivery.app.data.repository.SyncManager
import com.delivery.app.data.OfficeManager
import com.delivery.app.utils.AppLogger
import com.delivery.app.utils.NotificationHelper
import com.delivery.app.utils.WebSocketClient
import com.delivery.app.workers.DailySummaryWorker
import com.delivery.app.workers.PendingOrdersWorker
import com.delivery.app.workers.SyncWorker

class DeliveryApplication : Application() {
    val database by lazy { DeliveryDatabase.getDatabase(this) }
    val repository by lazy { OrderRepository(database.orderDao()) }
    val driverRepository by lazy { DriverRepository(database.driverDao()) }
    val customerRepository by lazy { CustomerRepository(database.customerDao()) }
    val syncManager by lazy { SyncManager(this, database.orderDao()) }
    val officeRepository by lazy { OfficeRepository(database.officeDao()) }
    val statusHistoryDao by lazy { database.statusHistoryDao() }
    val webSocketClient by lazy { WebSocketClient(this) }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(database.errorLogDao())
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e("CRASH", "Uncaught exception in ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        try {
            OfficeManager.init(this)
            NotificationHelper.createNotificationChannels(this)
            webSocketClient.connect()
            PendingOrdersWorker.schedule(this)
            DailySummaryWorker.schedule(this)
            SyncWorker.schedule(this)
        } catch (e: Exception) {
            AppLogger.e("DeliveryApp", "Init error", e)
        }
    }
}