package com.delivery.app.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.delivery.app.data.local.CustomerAggregatedStat
import com.delivery.app.data.local.CustomerDao
import com.delivery.app.data.model.Customer

class CustomerRepository(private val dao: CustomerDao) {
    fun getAllCustomers(officeId: Long = 0): LiveData<List<Customer>> =
        dao.getAllCustomers(officeId).map { list -> list.map { Customer.decryptPii(it) } }

    fun searchCustomers(query: String, officeId: Long = 0): LiveData<List<Customer>> =
        dao.searchCustomers(query, officeId).map { list -> list.map { Customer.decryptPii(it) } }

    fun getOrderCount(name: String, officeId: Long = 0): LiveData<Int> = dao.getOrderCount(name, officeId)
    fun getDeliveredCount(name: String, officeId: Long = 0): LiveData<Int> = dao.getDeliveredCount(name, officeId)
    fun getAllCustomerStats(officeId: Long = 0): LiveData<List<CustomerAggregatedStat>> = dao.getAllCustomerStats(officeId)
    suspend fun insertCustomer(customer: Customer) = dao.insertCustomer(Customer.encryptPii(customer))
    suspend fun deleteCustomer(customer: Customer) = dao.deleteCustomer(customer)
}
