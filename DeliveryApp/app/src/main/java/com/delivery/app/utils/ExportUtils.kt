package com.delivery.app.utils

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.delivery.app.data.model.Order
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ExportUtils {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())

    private fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n') ||
            value.startsWith('=') || value.startsWith('+') || value.startsWith('-') || value.startsWith('@')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    fun exportToCsv(context: Context, orders: List<Order>, periodName: String, includePii: Boolean = false) {
        val fileName = "تقرير_${periodName}_${fileNameFormat.format(Date())}.csv"
        val dir = context.getExternalFilesDir("ExternalExports")
        if (dir != null && !dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)

        val content = buildString {
            appendLine("اسم الزبون,نوع الطلبية,الكمية,العنوان,السائق,سعر الشراء,سعر التوصيل,الحالة,التاريخ")
            orders.forEach { order ->
                val name = if (includePii) order.customerName else "***"
                val address = if (includePii) order.deliveryAddress else "***"
                appendLine(
                    "${escapeCsv(name)},${escapeCsv(order.orderType)},${order.quantity}," +
                    "${escapeCsv(address)},${escapeCsv(order.driverName)}," +
                    "${order.purchasePrice.toLong()},${order.deliveryPrice.toLong()}," +
                    "${escapeCsv(order.statusEnum.label)},${escapeCsv(dateFormat.format(Date(order.createdAt)))}"
                )
            }
            val totalDelivery = orders.filter { it.isDelivered }.sumOf { it.deliveryPrice }
            val totalPurchase = orders.filter { it.isDelivered }.sumOf { it.purchasePrice }
            appendLine()
            appendLine("الاجمالي,,,,,${totalPurchase.toLong()},${totalDelivery.toLong()},,")
        }

        file.writeText(content, Charsets.UTF_8)
        shareFile(context, file, "text/csv")
    }

    fun exportToPdf(context: Context, orders: List<Order>, periodName: String, includePii: Boolean = false) {
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (dir != null && !dir.exists()) dir.mkdirs()

            val fileName = "تقرير_${periodName}_${fileNameFormat.format(Date())}.pdf"
            val file = File(dir, fileName)

            val document = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842

            val titlePaint = Paint().apply {
                textSize = 16f
                isFakeBoldText = true
                color = Color.parseColor("#1565C0")
                textAlign = Paint.Align.LEFT
            }
            val headerPaint = Paint().apply {
                textSize = 12f
                isFakeBoldText = true
                color = Color.BLACK
            }
            val normalPaint = Paint().apply {
                textSize = 10f
                color = Color.DKGRAY
            }
            val linePaint = Paint().apply {
                color = Color.LTGRAY
                strokeWidth = 1f
            }

            var pageNumber = 1
            var page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            )
            var canvas = page.canvas
            var yPos = 50f

            fun newPage() {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                )
                canvas = page.canvas
                yPos = 50f
            }

            fun checkPage() { if (yPos > 780f) newPage() }

            fun drawText(text: String, paint: Paint, x: Float = 40f) {
                checkPage()
                canvas.drawText(text, x, yPos, paint)
                yPos += paint.textSize + 6f
            }

            // العنوان
            drawText("Delivery Report - $periodName", titlePaint)
            drawText(dateFormat.format(Date()), normalPaint)
            canvas.drawLine(40f, yPos, (pageWidth - 40f), yPos, linePaint)
            yPos += 15f

            // الملخص
            val delivered = orders.count { it.isDelivered }
            val totalDelivery = orders.filter { it.isDelivered }.sumOf { it.deliveryPrice }
            val totalPurchase = orders.filter { it.isDelivered }.sumOf { it.purchasePrice }

            drawText("Summary:", headerPaint)
            drawText("Total Orders: ${orders.size}", normalPaint)
            drawText("Delivered: $delivered", normalPaint)
            drawText("Delivery Revenue: ${totalDelivery.toLong()} SYP", normalPaint)
            drawText("Purchase Total: ${totalPurchase.toLong()} SYP", normalPaint)
            canvas.drawLine(40f, yPos, (pageWidth - 40f), yPos, linePaint)
            yPos += 15f

            // تفاصيل الطلبات
            drawText("Order Details:", headerPaint)
            yPos += 5f

            orders.forEach { order ->
                checkPage()
                val name = if (includePii) order.customerName else "***"
                val address = if (includePii) order.deliveryAddress else "***"
                drawText("#${order.orderNumber} - $name", headerPaint)
                drawText("Type: ${order.orderType} | Qty: ${order.quantity}", normalPaint)
                drawText("Address: $address", normalPaint)
                if (order.driverName.isNotEmpty()) {
                    drawText("Driver: ${order.driverName}", normalPaint)
                }
                drawText(
                    "Purchase: ${order.purchasePrice.toLong()} | Delivery: ${order.deliveryPrice.toLong()} SYP",
                    normalPaint
                )
                drawText(
                    "Status: ${order.statusEnum.label} | ${dateFormat.format(Date(order.createdAt))}",
                    normalPaint
                )
                checkPage()
                canvas.drawLine(50f, yPos, (pageWidth - 50f), yPos, linePaint)
                yPos += 12f
            }

            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
            document.close()

            shareFile(context, file, "application/pdf")

        } catch (e: Exception) {
            Log.e("ExportUtils", "PDF export failed", e)
            android.widget.Toast.makeText(
                context,
                "فشل إنشاء PDF: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة التقرير"))
    }
}