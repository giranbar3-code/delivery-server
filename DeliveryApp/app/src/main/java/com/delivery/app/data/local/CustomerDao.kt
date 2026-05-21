package com.delivery.app.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.delivery.app.data.model.Customer

@Dao
interface CustomerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: Customer): Long

    @Delete
    suspend fun deleteCustomer(customer: Customer)

    @Query("SELECT * FROM customers WHERE officeId = :officeId ORDER BY name ASC")
    fun getAllCustomers(officeId: Long = 0): LiveData<List<Customer>>

    @Query("SELECT * FROM customers WHERE (name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%') AND officeId = :officeId ORDER BY name ASC")
    fun searchCustomers(query: String, officeId: Long = 0): LiveData<List<Customer>>

    @Query("SELECT COUNT(*) FROM orders WHERE customerName = :name AND orders.officeId = :officeId")
    fun getOrderCount(name: String, officeId: Long = 0): LiveData<Int>

    @Query("SELECT COUNT(*) FROM orders WHERE customerName = :name AND deliveryStatus = 'DELIVERED' AND orders.officeId = :officeId")
    fun getDeliveredCount(name: String, officeId: Long = 0): LiveData<Int>

    @Query("""
        SELECT customerName as customerName,
               COUNT(*) as totalOrders,
               SUM(CASE WHEN deliveryStatus = 'DELIVERED' THEN 1 ELSE 0 END) as deliveredCount
        FROM orders WHERE customerName != '' AND customerName IS NOT NULL AND orders.officeId = :officeId
        GROUP BY customerName
    """)
    fun getAllCustomerStats(officeId: Long = 0): LiveData<List<CustomerAggregatedStat>>
}

data class CustomerAggregatedStat(
    val customerName: String,
    val totalOrders: Int,
    val deliveredCount: Int
)
