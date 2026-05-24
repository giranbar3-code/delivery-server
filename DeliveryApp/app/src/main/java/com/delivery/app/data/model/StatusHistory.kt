package com.delivery.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "status_history")
data class StatusHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val orderId: Long,
    val fromStatus: String,
    val toStatus: String,
    val changedAt: Long = System.currentTimeMillis()
)
