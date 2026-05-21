package com.delivery.app.ui.customers

import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.delivery.app.data.model.Customer
import com.delivery.app.databinding.ItemCustomerBinding

class CustomerAdapter(
    private val onCustomerClick: (Customer) -> Unit,
    private val onDelete: (Customer) -> Unit
) : ListAdapter<Customer, CustomerAdapter.ViewHolder>(DiffCallback()) {

    private var orderCounts = mapOf<Long, Int>()
    private var deliveredCounts = mapOf<Long, Int>()

    fun updateCounts(orders: Map<Long, Int>, delivered: Map<Long, Int>) {
        orderCounts = orders
        deliveredCounts = delivered
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCustomerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCustomerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(customer: Customer) {
            binding.tvName.text = customer.name
            binding.tvPhone.text = "📞 ${customer.phone}"
            binding.tvAddress.text = customer.address.ifEmpty { "بدون عنوان" }

            val total = orderCounts[customer.id] ?: 0
            val delivered = deliveredCounts[customer.id] ?: 0
            binding.tvStats.text = "إجمالي $total | تم $delivered"

            binding.root.setOnClickListener {
                onCustomerClick(customer)
            }

            binding.btnDelete.setOnClickListener {
                showDeleteConfirmation(customer)
            }

            binding.root.setOnLongClickListener {
                copyToClipboard(customer)
                true
            }
        }

        private fun showDeleteConfirmation(customer: Customer) {
            val context = binding.root.context
            AlertDialog.Builder(context)
                .setTitle("حذف عميل")
                .setMessage("هل تريد حذف العميل ${customer.name}؟")
                .setPositiveButton("حذف") { _, _ ->
                    onDelete(customer)
                    Toast.makeText(context, "تم حذف العميل", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        private fun copyToClipboard(customer: Customer) {
            val context = binding.root.context
            val text = "العميل: ${customer.name}\nالهاتف: ${customer.phone}\nالعنوان: ${customer.address}"
            AlertDialog.Builder(context)
                .setTitle("نسخ بيانات العميل")
                .setMessage("سيتم نسخ بيانات العميل (الاسم، الهاتف، العنوان) إلى الحافظة — أي تطبيق آخر يمكنه قراءتها.")
                .setPositiveButton("نسخ") { _, _ ->
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("عميل", text))
                    Toast.makeText(context, "تم نسخ بيانات العميل", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Customer>() {
        override fun areItemsTheSame(old: Customer, new: Customer) = old.id == new.id
        override fun areContentsTheSame(old: Customer, new: Customer) = old == new
    }
}
