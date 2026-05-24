package com.delivery.app.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.delivery.app.R
import com.delivery.app.data.model.ErrorLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsAdapter : ListAdapter<ErrorLog, LogsAdapter.ViewHolder>(DiffCallback) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val levelIndicator: View = itemView.findViewById(R.id.level_indicator)
        private val tvLevel: TextView = itemView.findViewById(R.id.tv_log_level)
        private val tvTag: TextView = itemView.findViewById(R.id.tv_log_tag)
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_log_message)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_log_time)

        fun bind(log: ErrorLog) {
            val context = itemView.context
            tvLevel.text = log.level
            tvTag.text = log.tag
            tvMessage.text = log.message
            tvTime.text = formatTime(log.timestamp)

            val color = when (log.level) {
                "ERROR" -> R.color.error
                "WARN" -> R.color.warning
                "DEBUG" -> R.color.primary_blue
                else -> R.color.success
            }
            levelIndicator.setBackgroundTintList(ContextCompat.getColorStateList(context, color))
            tvLevel.setTextColor(ContextCompat.getColor(context, color))

            itemView.setOnClickListener {
                val text = "[${log.level}] ${log.tag}: ${log.message}" +
                    (if (log.stackTrace != null) "\n\n${log.stackTrace}" else "")
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("log", text))
                Toast.makeText(context, "تم نسخ السجل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "الآن"
            diff < 3600_000 -> "${diff / 60_000} دقيقة"
            diff < 86400_000 -> timeFormat.format(Date(timestamp))
            else -> dateFormat.format(Date(timestamp))
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ErrorLog>() {
        override fun areItemsTheSame(oldItem: ErrorLog, newItem: ErrorLog) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ErrorLog, newItem: ErrorLog) = oldItem == newItem
    }
}
