package com.delivery.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offices")
data class Office(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = ""
)
