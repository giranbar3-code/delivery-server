package com.delivery.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "error_logs")
data class ErrorLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val level: String,
    val tag: String,
    val message: String,
    val stackTrace: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
