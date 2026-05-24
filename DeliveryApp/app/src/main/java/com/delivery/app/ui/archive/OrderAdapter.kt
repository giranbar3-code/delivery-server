package com.delivery.app.ui.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.delivery.app.R
import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.data.model.Driver
import com.delivery.app.data.model.Order
import com.delivery.app.databinding.ItemOrderBinding
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class OrderAdapter(
    private val onEdit: (Order) -> Unit,
    private val onDelete: (Order) -> Unit,
    private val onStatusChange: (Order, DeliveryStatus) -> Unit,
    private val onPrint: ((Order) -> Unit)? = null,
    private val onAssignDriver: ((Order) -> Unit)? = null,
    private val onShowStatusHistory: ((Order) -> Unit)? = null
) : ListAdapter<Order, OrderAdapter.OrderViewHolder>(DiffCallback) {

    private val fullDateFormat = SimpleDateFormat("dd/MM/yyyy - hh:mm a", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEEE، dd MMMM yyyy", Locale("ar"))
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale("ar"))
    private var driverPhoneMap = mapOf<String, String>()

    var selectionMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    private val selectedIds = mutableSetOf<Long>()

    val selectedOrders: Set<Long> get() = selectedIds
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()

    fun toggleSelection(orderId: Long) {
        if (selectedIds.contains(orderId)) selectedIds.remove(orderId)
        else selectedIds.add(orderId)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        selectionMode = false
        notifyDataSetChanged()
    }

    fun setDriverPhoneMap(map: Map<String, String>) {
        driverPhoneMap = map
    }

    inner class OrderViewHolder(private val binding: ItemOrderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order) {
            binding.tvCustomerName.text = order.customerName
            binding.tvOrderNumber.text = "طلبية #${order.orderNumber}"
            if (order.source == "web") {
                binding.tvOrderNumber.text = if (order.verified)
                    "طلبية #${order.orderNumber} ✓ موثوق"
                else
                    "طلبية #${order.orderNumber} ⚠ غير موثوق"
            }
            binding.tvQuantity.text = "الكمية: ${order.quantity}"

            val itemsContainer = binding.root.findViewById<LinearLayout>(R.id.items_container)
            itemsContainer?.removeAllViews()
            try {
                val itemsArray = JSONArray(order.items)
                if (itemsArray.length() > 0) {
                    itemsContainer?.visibility = View.VISIBLE
                    for (i in 0 until itemsArray.length()) {
                        val item = itemsArray.getJSONObject(i)
                        val name = item.optString("name", "")
                        val qty = item.optInt("quantity", 1)
                        if (name.isNotEmpty()) {
                            val tv = TextView(binding.root.context).apply {
                                text = "• $name × $qty"
                                textSize = 14f
                                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                                setPadding(0, 2, 0, 2)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            }
                            itemsContainer?.addView(tv)
                        }
                    }
                } else {
                    itemsContainer?.visibility = View.GONE
                }
            } catch (e: Exception) {
                itemsContainer?.visibility = View.GONE
            }

            binding.tvAddress.text = order.deliveryAddress
            binding.tvAddress.setCompoundDrawablesRelativeWithIntrinsicBounds(
                ContextCompat.getDrawable(binding.root.context, android.R.drawable.ic_menu_compass), null, null, null
            )

            // ✅ رقم الزبون — اتصال + واتساب (فقط للطلبيات غير المكتملة)
            val isCompleted = order.deliveryStatus in listOf("DELIVERED", "RETURNED", "CANCELLED")
            if (order.customerPhone.isNotEmpty()) {
                binding.rowPhone.visibility = View.VISIBLE
                binding.tvPhone.text = "📞 ${order.customerPhone}"

                binding.btnCall.setOnClickListener {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.customerPhone}"))
                    binding.root.context.startActivity(intent)
                }

                binding.btnWhatsappCustomer.visibility = if (isCompleted) View.GONE else View.VISIBLE
                binding.btnWhatsappCustomer.setOnClickListener {
                    openWhatsApp(order.customerPhone)
                }

                binding.btnNotifyWhatsapp.visibility = if (isCompleted) View.GONE else View.VISIBLE
                binding.btnNotifyWhatsapp.setOnClickListener {
                    notifyCustomerViaWhatsApp(order)
                }
            } else {
                binding.rowPhone.visibility = View.GONE
                binding.btnNotifyWhatsapp.visibility = View.GONE
            }

            // زر الخريطة
            if (order.locationUrl.isNotEmpty()) {
                binding.btnOpenMap.visibility = View.VISIBLE
                binding.btnOpenMap.setOnClickListener {
                    openLocationInMaps(order.locationUrl)
                }
            } else {
                binding.btnOpenMap.visibility = View.GONE
            }

            // ✅ السائق + واتساب السائق (فقط للطلبيات غير المكتملة)
            if (order.driverName.isNotEmpty()) {
                binding.rowDriver.visibility = View.VISIBLE
                binding.tvDriver.text = "السائق: ${order.driverName}"

                val driverPhone = driverPhoneMap[order.driverName]
                if (driverPhone != null && !isCompleted) {
                    binding.btnWhatsappDriver.visibility = View.VISIBLE
                    binding.btnWhatsappDriver.setOnClickListener { sendOrderToDriverWhatsApp(order, driverPhone) }
                } else {
                    binding.btnWhatsappDriver.visibility = View.GONE
                }
            } else {
                binding.rowDriver.visibility = View.GONE
            }

            binding.rowDriver.setOnClickListener { onAssignDriver?.invoke(order) }
            binding.btnChangeDriver.setOnClickListener { onAssignDriver?.invoke(order) }

            binding.tvDate.text = formatSmartDate(order.createdAt)

            // ✅ ملاحظات
            if (order.notes.isNotEmpty()) {
                binding.tvNotes.visibility = View.VISIBLE
                binding.tvNotes.text = "📝 ${order.notes}"
            } else {
                binding.tvNotes.visibility = View.GONE
            }

            // ✅ حالة التوصيل الجديدة
            val status = order.statusEnum
            binding.tvStatus.text = "${status.emoji} ${status.label}"
            val statusColor = getStatusColor(status)
            binding.tvStatus.setTextColor(statusColor)
            binding.cardRoot.strokeColor = statusColor
            binding.cardRoot.strokeWidth = 0

            // ✅ النقر على badge → يفتح قائمة الحالات
            binding.tvStatus.setOnClickListener {
                showStatusDialog(order)
            }
            binding.tvStatus.setOnLongClickListener {
                onShowStatusHistory?.invoke(order)
                true
            }

            // ✅ checkbox للتحديد المتعدد
            val isSelected = selectedIds.contains(order.id)
            binding.cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = isSelected
            binding.cbSelect.setOnClickListener {
                toggleSelection(order.id)
            }
            if (selectionMode) {
                binding.root.setOnClickListener { toggleSelection(order.id) }
            }
            binding.cardRoot.setCardBackgroundColor(
                ContextCompat.getColor(binding.root.context,
                    if (isSelected) R.color.selection_highlight else R.color.surface_white)
            )

            binding.btnEdit.setOnClickListener { onEdit(order) }

            binding.btnPrint.visibility = if (selectionMode) View.GONE else View.VISIBLE
            binding.btnPrint.setOnClickListener {
                onPrint?.invoke(order)
            }

            binding.btnDelete.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(order.id)
                } else {
                    showDeleteConfirmation(order)
                }
            }
        }

        // ✅ فتح واتساب برقم الهاتف
        private fun notifyCustomerViaWhatsApp(order: Order) {
            val context = binding.root.context
            if (order.customerPhone.isEmpty()) {
                Toast.makeText(context, "لا يوجد رقم هاتف للزبون", Toast.LENGTH_SHORT).show()
                return
            }
            val message = buildString {
                appendLine("توصيلة جديدة")
                appendLine("الحالة: ${order.statusEnum.emoji} ${order.statusEnum.label}")
                appendLine()
                appendLine("الزبون: ${order.customerName}")
                appendLine("الهاتف: ${order.customerPhone}")
                appendLine("العنوان: ${order.deliveryAddress}")
                if (order.locationUrl.isNotEmpty()) {
                    appendLine()
                    appendLine("الموقع: ${order.locationUrl}")
                }
                appendLine()
                appendLine(fullDateFormat.format(Date(order.createdAt)))
            }
            val cleanPhone = order.customerPhone.trim().replace("[^0-9+]".toRegex(), "")
            val internationalPhone = when {
                cleanPhone.startsWith("+") -> cleanPhone
                cleanPhone.startsWith("00") -> "+${cleanPhone.substring(2)}"
                cleanPhone.startsWith("0") -> "+963${cleanPhone.substring(1)}"
                else -> "+963$cleanPhone"
            }
            Log.d("WA_Customer", "message=[$message] phone=[$internationalPhone]")
            openWhatsAppWithMessage(context, internationalPhone, message)
        }

        private fun openWhatsAppWithMessage(context: Context, phone: String, message: String) {
            try {
                val uri = Uri.Builder()
                    .scheme("https")
                    .authority("wa.me")
                    .path("/$phone")
                    .appendQueryParameter("text", message)
                    .build()
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                return
            } catch (e: Exception) {
                Log.w("WhatsApp", "wa.me فشل", e)
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$phone&text=${Uri.encode(message)}"))
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w("WhatsApp", "whatsapp:// send فشل", e)
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(message)}"))
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w("WhatsApp", "api.whatsapp.com فشل", e)
            }
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    `package` = "com.whatsapp"
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "تعذّر فتح واتساب", Toast.LENGTH_SHORT).show()
            }
        }

        private fun openWhatsApp(phone: String) {
            val context = binding.root.context
            val cleanPhone = phone.trim().replace("[^0-9+]".toRegex(), "")
            val internationalPhone = when {
                cleanPhone.startsWith("+") -> cleanPhone
                cleanPhone.startsWith("00") -> "+${cleanPhone.substring(2)}"
                cleanPhone.startsWith("0") -> "+963${cleanPhone.substring(1)}"
                else -> "+963$cleanPhone"
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/$internationalPhone"))
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "تعذّر فتح واتساب", Toast.LENGTH_SHORT).show()
            }
        }

        private fun sendOrderToDriverWhatsApp(order: Order, driverPhone: String) {
            val context = binding.root.context
            val itemsText = try {
                val arr = org.json.JSONArray(order.items)
                (0 until arr.length()).joinToString("\n") { i ->
                    val item = arr.getJSONObject(i)
                    "• ${item.optString("name", "")} ×${item.optInt("quantity", 1)}"
                }
            } catch (e: Exception) { order.orderType }
            val message = buildString {
                appendLine("توصيلة جديدة")
                appendLine("الحالة: ${order.statusEnum.emoji} ${order.statusEnum.label}")
                appendLine()
                appendLine("الزبون: ${order.customerName}")
                appendLine("الهاتف: ${order.customerPhone}")
                appendLine("العنوان: ${order.deliveryAddress}")
                if (order.locationUrl.isNotEmpty()) {
                    appendLine()
                    appendLine("الموقع: ${order.locationUrl}")
                }
                appendLine()
                appendLine("المواد:")
                appendLine(itemsText)
                if (order.notes.isNotEmpty()) {
                    appendLine()
                    appendLine("ملاحظات: ${order.notes}")
                }
                appendLine()
                appendLine(fullDateFormat.format(Date(order.createdAt)))
            }
            val cleanPhone = driverPhone.trim().replace("[^0-9+]".toRegex(), "")
            val internationalPhone = when {
                cleanPhone.startsWith("+") -> cleanPhone
                cleanPhone.startsWith("00") -> "+${cleanPhone.substring(2)}"
                cleanPhone.startsWith("0") -> "+963${cleanPhone.substring(1)}"
                else -> "+963$cleanPhone"
            }
            Log.d("WA_Driver", "message=[$message] phone=[$internationalPhone]")
            openWhatsAppWithMessage(context, internationalPhone, message)
        }

        // ✅ قائمة اختيار الحالة
        private fun showStatusDialog(order: Order) {
            val context = binding.root.context
            val statuses = DeliveryStatus.values()
            val labels = statuses.map { "${it.emoji} ${it.label}" }.toTypedArray()

            AlertDialog.Builder(context)
                .setTitle("تغيير حالة الطلبية")
                .setSingleChoiceItems(labels, statuses.indexOf(order.statusEnum)) { dialog, which ->
                    onStatusChange(order, statuses[which])
                    dialog.dismiss()
                }
                .setNegativeButton("إلغاء") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        private fun getStatusColor(status: DeliveryStatus): Int {
            val context = binding.root.context
            return when (status) {
                DeliveryStatus.PENDING         -> ContextCompat.getColor(context, R.color.status_pending)
                DeliveryStatus.PREPARING       -> ContextCompat.getColor(context, R.color.status_preparing)
                DeliveryStatus.OUT_FOR_DELIVERY -> ContextCompat.getColor(context, R.color.status_out_for_delivery)
                DeliveryStatus.DELIVERED       -> ContextCompat.getColor(context, R.color.status_delivered)
                DeliveryStatus.RETURNED        -> ContextCompat.getColor(context, R.color.status_returned)
                DeliveryStatus.CANCELLED       -> ContextCompat.getColor(context, R.color.status_cancelled)
            }
        }

        private fun showDeleteConfirmation(order: Order) {
            AlertDialog.Builder(binding.root.context)
                .setTitle("تأكيد الحذف")
                .setMessage("هل أنت متأكد من حذف طلبية\n\"${order.customerName}\"؟\n\nلا يمكن التراجع عن هذا الإجراء.")
                .setPositiveButton("حذف") { dialog, _ ->
                    onDelete(order)
                    dialog.dismiss()
                }
                .setNegativeButton("إلغاء") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
                .also { dialog ->
                    dialog?.getButton(AlertDialog.BUTTON_POSITIVE)
                        ?.setTextColor(
                            ContextCompat.getColor(binding.root.context, R.color.status_cancelled)
                        )
                }
        }

        private fun openLocationInMaps(url: String) {
            try {
                val uri = Uri.parse(url)
                if (uri.scheme !in listOf("http", "https", "geo")) return
                val context = binding.root.context
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage("com.google.android.apps.maps")
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            } catch (e: Exception) { }
        }

        private fun formatSmartDate(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diffMs = now - timestamp
            val diffMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
            val diffHours = TimeUnit.MILLISECONDS.toHours(diffMs)
            val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)

            val orderCal = Calendar.getInstance().apply { timeInMillis = timestamp }
            val todayCal = Calendar.getInstance()
            val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val timeStr = timeFormat.format(Date(timestamp))

            return when {
                diffMinutes < 1 -> "الآن"
                diffMinutes < 60 -> "منذ $diffMinutes دقيقة"
                diffHours < 24 && isSameDay(orderCal, todayCal) -> "اليوم - $timeStr"
                isSameDay(orderCal, yesterdayCal) -> "أمس - $timeStr"
                diffDays < 7 -> "${dayFormat.format(Date(timestamp)).split("،")[0]} - $timeStr"
                else -> fullDateFormat.format(Date(timestamp))
            }
        }

        private fun isSameDay(cal1: Calendar, cal2: Calendar) =
            cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                    cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Order>() {
        override fun areItemsTheSame(oldItem: Order, newItem: Order) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Order, newItem: Order) = oldItem == newItem
    }
}
