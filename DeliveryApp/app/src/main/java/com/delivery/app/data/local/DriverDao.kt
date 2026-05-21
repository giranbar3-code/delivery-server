package com.delivery.app.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.delivery.app.data.model.Driver

@Dao
interface DriverDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDriver(driver: Driver): Long

    @Update
    suspend fun updateDriver(driver: Driver)

    @Delete
    suspend fun deleteDriver(driver: Driver)

    @Query("SELECT * FROM drivers WHERE officeId = :officeId ORDER BY name ASC")
    fun getAllDrivers(officeId: Long = 0): LiveData<List<Driver>>

    @Query("SELECT COUNT(*) FROM orders WHERE driverName = :name AND orders.officeId = :officeId")
    fun getOrderCount(name: String, officeId: Long = 0): LiveData<Int>

    // ✅ مُصلَح: استبدال isDelivered = 1 بـ deliveryStatus = 'DELIVERED'
    @Query("SELECT COUNT(*) FROM orders WHERE driverName = :name AND deliveryStatus = 'DELIVERED' AND orders.officeId = :officeId")
    fun getDeliveredCount(name: String, officeId: Long = 0): LiveData<Int>

    @Query("SELECT SUM(deliveryPrice) FROM orders WHERE driverName = :name AND deliveryStatus = 'DELIVERED' AND orders.officeId = :officeId")
    fun getTotalRevenue(name: String, officeId: Long = 0): LiveData<Double?>
}
