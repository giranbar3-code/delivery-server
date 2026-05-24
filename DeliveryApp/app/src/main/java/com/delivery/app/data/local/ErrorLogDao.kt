package com.delivery.app.data.local

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.delivery.app.data.model.ErrorLog

@Dao
interface ErrorLogDao {

    @Insert
    suspend fun insert(log: ErrorLog)

    @Query("SELECT * FROM error_logs ORDER BY timestamp DESC")
    fun getAll(): LiveData<List<ErrorLog>>

    @Query("SELECT * FROM error_logs WHERE level = :level ORDER BY timestamp DESC")
    fun getByLevel(level: String): LiveData<List<ErrorLog>>

    @Query("DELETE FROM error_logs")
    suspend fun deleteAll()

    @Query("DELETE FROM error_logs WHERE id NOT IN (SELECT id FROM error_logs ORDER BY timestamp DESC LIMIT :maxCount)")
    suspend fun trimTo(maxCount: Int)
}
