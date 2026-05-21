package com.delivery.app.data.repository

import androidx.lifecycle.LiveData
import com.delivery.app.data.local.OfficeDao
import com.delivery.app.data.model.Office

class OfficeRepository(private val dao: OfficeDao) {
    fun getAllOffices(): LiveData<List<Office>> = dao.getAllOffices()
    suspend fun getAllOfficesSync(): List<Office> = dao.getAllOfficesSync()
    fun getOfficeById(id: Long): LiveData<Office?> = dao.getOfficeById(id)
    suspend fun getOfficeByIdSync(id: Long): Office? = dao.getOfficeByIdSync(id)
    fun getOfficeCount(): LiveData<Int> = dao.getOfficeCount()
    suspend fun insertOffice(office: Office): Long = dao.insertOffice(office)
    suspend fun updateOffice(office: Office) = dao.updateOffice(office)
    suspend fun deleteOffice(office: Office) = dao.deleteOffice(office)
}
