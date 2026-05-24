package com.delivery.app.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.delivery.app.data.local.DriverDao
import com.delivery.app.data.model.Driver

class DriverRepository(private val dao: DriverDao) {
    fun getAllDrivers(officeId: Long = 0): LiveData<List<Driver>> =
        dao.getAllDrivers(officeId).map { list -> list.map { Driver.decryptPii(it) } }

    fun getOrderCount(name: String, officeId: Long = 0): LiveData<Int> = dao.getOrderCount(name, officeId)
    fun getDeliveredCount(name: String, officeId: Long = 0): LiveData<Int> = dao.getDeliveredCount(name, officeId)
    fun getTotalRevenue(name: String, officeId: Long = 0): LiveData<Double?> = dao.getTotalRevenue(name, officeId)
    suspend fun getDriversSync(officeId: Long = 0): List<Driver> =
        dao.getDriversSync(officeId).map { Driver.decryptPii(it) }

    suspend fun insertDriver(driver: Driver) = dao.insertDriver(Driver.encryptPii(driver))
    suspend fun updateDriver(driver: Driver) = dao.updateDriver(Driver.encryptPii(driver))
    suspend fun deleteDriver(driver: Driver) = dao.deleteDriver(driver)
}
