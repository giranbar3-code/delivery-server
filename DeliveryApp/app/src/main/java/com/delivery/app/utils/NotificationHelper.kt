package com.delivery.app.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.delivery.app.MainActivity

object NotificationHelper {

    private const val CHANNEL_PENDING = "channel_pending_orders"
    private const val CHANNEL_STATS   = "channel_daily_stats"
    private const val CHANNEL_SYNC    = "channel_sync"

    fun createNotificationChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        NotificationChannel(
            CHANNEL_PENDING, "الطلبيات المعلّقة",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "تذكير بالطلبيات التي لم يتم توصيلها"
            manager.createNotificationChannel(this)
        }

        NotificationChannel(
            CHANNEL_STATS, "ملخص يومي",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "ملخص إحصائيات اليوم"
            manager.createNotificationChannel(this)
        }

        NotificationChannel(
            CHANNEL_SYNC, "طلبيات جديدة",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "إشعار عند وصول طلبيات جديدة من الخادم"
            manager.createNotificationChannel(this)
        }
    }

    fun notifyPendingOrders(context: Context, pendingCount: Int) {
        if (pendingCount == 0) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PENDING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("طلبيات معلّقة 📦")
            .setContentText("يوجد $pendingCount طلبية لم يتم توصيلها بعد")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("يوجد $pendingCount طلبية لم يتم توصيلها بعد.\nاضغط للمراجعة."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1001, notification)
        } catch (e: SecurityException) { }
    }

    fun notifyDailySummary(
        context: Context, deliveredCount: Int,
        totalCount: Int, revenue: Double
    ) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_STATS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ملخص اليوم 📊")
            .setContentText("وُصِّل $deliveredCount من $totalCount | الإيراد: ${revenue.toInt()} ل.س")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(
                    "📦 إجمالي الطلبيات: $totalCount\n" +
                    "✅ تم التوصيل: $deliveredCount\n" +
                    "⏳ معلّقة: ${totalCount - deliveredCount}\n" +
                    "💰 إيرادات: ${revenue.toInt()} ل.س"
                ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1002, notification)
        } catch (e: SecurityException) { }
    }

    fun notifyNewOrders(context: Context, count: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("طلبيات جديدة 🆕")
            .setContentText("وصلت $count طلبية جديدة من الموقع!")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("وصلت $count طلبية جديدة من الموقع!\nاضغط لعرضها في الأرشيف."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(2001, notification)
        } catch (e: SecurityException) { }
    }
}