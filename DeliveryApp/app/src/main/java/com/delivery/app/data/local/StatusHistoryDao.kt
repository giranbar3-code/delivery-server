package com.delivery.app.data.local

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.delivery.app.data.model.StatusHistory

@Dao
interface StatusHistoryDao {

    @Insert
    suspend fun insert(history: StatusHistory)

    @Query("SELECT * FROM status_history WHERE orderId = :orderId ORDER BY changedAt ASC")
    fun getByOrderId(orderId: Long): LiveData<List<StatusHistory>>

    @Query("DELETE FROM status_history WHERE orderId = :orderId")
    suspend fun deleteByOrderId(orderId: Long)

    @Query("DELETE FROM status_history")
    suspend fun deleteAll()
}
