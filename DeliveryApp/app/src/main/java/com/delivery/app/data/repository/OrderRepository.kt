package com.delivery.app.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.delivery.app.data.local.DailyStat
import com.delivery.app.data.local.DriverAggregatedStat
import com.delivery.app.data.local.DriverStat
import com.delivery.app.data.local.OrderDao
import com.delivery.app.data.local.StatusStat
import com.delivery.app.data.model.Order

class OrderRepository(private val dao: OrderDao) {

    private fun decryptList(orders: List<Order>): List<Order> =
        orders.map { Order.decryptPii(it) }

    fun getAllOrders(officeId: Long = 0): LiveData<List<Order>> =
        dao.getAllOrders(officeId).map { decryptList(it) }

    fun getAllOrdersWithDelivered(officeId: Long = 0): LiveData<List<Order>> =
        dao.getAllOrdersWithDelivered(officeId).map { decryptList(it) }

    fun getDeliveredOrdersAll(officeId: Long = 0): LiveData<List<Order>> =
        dao.getDeliveredOrdersAll(officeId).map { decryptList(it) }

    fun getAllOrdersSortedByDelivery(officeId: Long = 0): LiveData<List<Order>> =
        dao.getAllOrdersSortedByDelivery(officeId).map { decryptList(it) }

    fun searchOrders(query: String, officeId: Long = 0): LiveData<List<Order>> =
        dao.searchOrders(query, officeId).map { decryptList(it) }

    fun getOrdersByDateRange(s: Long, e: Long, officeId: Long = 0): LiveData<List<Order>> =
        dao.getOrdersByDateRange(s, e, officeId).map { decryptList(it) }

    fun searchOrdersByDateRange(q: String, s: Long, e: Long, officeId: Long = 0): LiveData<List<Order>> =
        dao.searchOrdersByDateRange(q, s, e, officeId).map { decryptList(it) }

    fun getDeliveredOrdersSince(startTime: Long, officeId: Long = 0): LiveData<List<Order>> =
        dao.getDeliveredOrdersSince(startTime, officeId).map { decryptList(it) }

    fun getDeliveredCount(startTime: Long, officeId: Long = 0): LiveData<Int> =
        dao.getDeliveredCount(startTime, officeId)

    fun getTotalCount(startTime: Long, officeId: Long = 0): LiveData<Int> =
        dao.getTotalCount(startTime, officeId)

    fun getTotalDeliveryRevenue(startTime: Long, officeId: Long = 0): LiveData<Double?> =
        dao.getTotalDeliveryRevenue(startTime, officeId)

    fun getTotalPurchaseRevenue(startTime: Long, officeId: Long = 0): LiveData<Double?> =
        dao.getTotalPurchaseRevenue(startTime, officeId)

    fun getDriverStats(startTime: Long, officeId: Long = 0): LiveData<List<DriverStat>> =
        dao.getDriverStats(startTime, officeId)

    fun getAllDriverAggregatedStats(officeId: Long = 0): LiveData<List<DriverAggregatedStat>> =
        dao.getAllDriverAggregatedStats(officeId)

    fun getDailyStats(startTime: Long, officeId: Long = 0): LiveData<List<DailyStat>> =
        dao.getDailyStats(startTime, officeId)

    fun getStatusDistribution(startTime: Long, officeId: Long = 0): LiveData<List<StatusStat>> =
        dao.getStatusDistribution(startTime, officeId)

    suspend fun insertOrder(order: Order): Result<Long> = try {
        Result.success(dao.insertOrder(Order.encryptPii(order)))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun insertOrders(orders: List<Order>): Result<Unit> = try {
        dao.insertOrders(orders.map { Order.encryptPii(it) })
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateOrder(order: Order): Result<Unit> = try {
        dao.updateOrder(Order.encryptPii(order))
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun deleteOrder(order: Order): Result<Unit> = try {
        dao.deleteOrder(order)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getAllOrdersSync(): Result<List<Order>> = try {
        Result.success(decryptList(dao.getAllOrdersSync()))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getLastOrderNumber(): Result<Int> = try {
        Result.success(dao.getLastOrderNumber())
    } catch (e: Exception) {
        Result.failure(e)
    }
}
