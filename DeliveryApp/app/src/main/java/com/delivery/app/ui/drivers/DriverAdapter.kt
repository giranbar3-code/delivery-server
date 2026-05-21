package com.delivery.app.ui.drivers

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.delivery.app.R
import com.delivery.app.data.local.DriverAggregatedStat
import com.delivery.app.data.model.Driver
import com.delivery.app.databinding.ItemDriverBinding

class DriverAdapter(
    private val onEdit: (Driver) -> Unit,
    private val onDelete: (Driver) -> Unit
) : ListAdapter<Driver, DriverAdapter.DriverViewHolder>(DiffCallback) {

    private val statsMap = mutableMapOf<String, String>()

    fun updateStats(stats: List<DriverAggregatedStat>) {
        statsMap.clear()
        stats.forEach { stat ->
            statsMap[stat.driverName] =
                "توصيلات ${stat.deliveredCount}  ·  إيرادات ${stat.totalRevenue.toLong()} ل.س"
        }
        notifyItemRangeChanged(0, itemCount)
    }

    inner class DriverViewHolder(private val binding: ItemDriverBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(driver: Driver) {
            binding.tvDriverName.text = driver.name

            if (driver.phone.isNotEmpty()) {
                binding.tvDriverPhone.text = "📱 ${driver.phone}"
                binding.btnCallDriver.visibility = View.VISIBLE
                binding.btnWhatsappDriver.visibility = View.VISIBLE

                binding.btnCallDriver.setOnClickListener {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${driver.phone}"))
                    binding.root.context.startActivity(intent)
                }

                binding.btnWhatsappDriver.setOnClickListener {
                    openWhatsApp(driver.phone)
                }
            } else {
                binding.tvDriverPhone.text = "لا يوجد رقم"
                binding.btnCallDriver.visibility = View.GONE
                binding.btnWhatsappDriver.visibility = View.GONE
            }

            binding.tvDriverStats.text = statsMap[driver.name] ?: ""

            binding.btnEditDriver.setOnClickListener { onEdit(driver) }
            binding.btnDeleteDriver.setOnClickListener { showDeleteConfirmation(driver) }
        }

        private fun showDeleteConfirmation(driver: Driver) {
            AlertDialog.Builder(binding.root.context)
                .setTitle("تأكيد حذف السائق")
                .setMessage("هل أنت متأكد من حذف السائق\n\"${driver.name}\"؟\n\nلا يمكن التراجع عن هذا الإجراء.")
                .setPositiveButton("حذف") { dialog, _ ->
                    onDelete(driver)
                    dialog.dismiss()
                }
                .setNegativeButton("إلغاء") { dialog, _ -> dialog.dismiss() }
                .setCancelable(true)
                .show()
                .also { dialog ->
                    dialog?.getButton(AlertDialog.BUTTON_POSITIVE)
                        ?.setTextColor(
                            ContextCompat.getColor(binding.root.context, R.color.status_cancelled)
                        )
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DriverViewHolder {
        val binding = ItemDriverBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DriverViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DriverViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Driver>() {
        override fun areItemsTheSame(oldItem: Driver, newItem: Driver) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Driver, newItem: Driver) = oldItem == newItem
    }
}
