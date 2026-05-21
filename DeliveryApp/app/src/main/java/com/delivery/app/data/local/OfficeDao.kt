package com.delivery.app.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.delivery.app.data.model.Office

@Dao
interface OfficeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffice(office: Office): Long

    @Update
    suspend fun updateOffice(office: Office)

    @Delete
    suspend fun deleteOffice(office: Office)

    @Query("SELECT * FROM offices ORDER BY name ASC")
    fun getAllOffices(): LiveData<List<Office>>

    @Query("SELECT * FROM offices ORDER BY name ASC")
    suspend fun getAllOfficesSync(): List<Office>

    @Query("SELECT * FROM offices WHERE id = :id")
    fun getOfficeById(id: Long): LiveData<Office?>

    @Query("SELECT * FROM offices WHERE id = :id")
    suspend fun getOfficeByIdSync(id: Long): Office?

    @Query("SELECT COUNT(*) FROM offices")
    fun getOfficeCount(): LiveData<Int>
}
