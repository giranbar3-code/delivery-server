package com.delivery.app.utils

import android.content.Context
import android.net.Uri
import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.data.model.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

object BackupManager {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    suspend fun exportToCSV(context: Context, uri: Uri, orders: List<Order>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    val writer = stream.bufferedWriter(Charsets.UTF_8)
                    writer.write("\uFEFF")
                    writer.write("الرقم,اسم الزبون,نوع الطلبية,الكمية,عنوان التوصيل,السائق,سعر الشراء,سعر التوصيل,الحالة,تاريخ الإنشاء\n")
                    orders.forEach { order ->
                        // ✅ استخدام statusEnum بدل isDelivered
                        val status = order.statusEnum.label
                        val date = dateFormat.format(Date(order.createdAt))
                        writer.write("${order.id},\"${order.customerName}\",\"${order.orderType}\",${order.quantity},\"${order.deliveryAddress}\",\"${order.driverName}\",${order.purchasePrice},${order.deliveryPrice},\"$status\",\"$date\"\n")
                    }
                    writer.flush()
                }
                Result.success(orders.size)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun importFromCSV(context: Context, uri: Uri): Result<List<Order>> =
        withContext(Dispatchers.IO) {
            try {
                val orders = mutableListOf<Order>()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    var line = reader.readLine()
                    if (line != null && line.startsWith("\uFEFF")) line = line.substring(1)
                    line = reader.readLine() // تخطي الهيدر
                    while (line != null) {
                        parseLine(line)?.let { orders.add(it) }
                        line = reader.readLine()
                    }
                }
                Result.success(orders)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseLine(line: String): Order? {
        return try {
            val cols = splitCSVLine(line)
            if (cols.size < 10) return null

            // ✅ تحويل النص العربي للحالة إلى DeliveryStatus
            val statusLabel = cols[8].trim('"')
            val deliveryStatus = DeliveryStatus.values()
                .firstOrNull { it.label == statusLabel }?.name
                ?: if (statusLabel == "تم التوصيل") DeliveryStatus.DELIVERED.name
                   else DeliveryStatus.PENDING.name

            Order(
                customerName    = cols[1].trim('"'),
                orderType       = cols[2].trim('"'),
                quantity        = cols[3].toIntOrNull() ?: 1,
                deliveryAddress = cols[4].trim('"'),
                driverName      = cols[5].trim('"'),
                purchasePrice   = cols[6].toDoubleOrNull() ?: 0.0,
                deliveryPrice   = cols[7].toDoubleOrNull() ?: 0.0,
                deliveryStatus  = deliveryStatus,  // ✅
                createdAt       = try {
                    dateFormat.parse(cols[9].trim('"'))?.time ?: System.currentTimeMillis()
                } catch (e: Exception) { System.currentTimeMillis() }
            )
        } catch (e: Exception) { null }
    }

    private fun splitCSVLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }
}
