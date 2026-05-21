package com.delivery.app.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.data.model.Order

@Dao
interface OrderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: Order): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrders(orders: List<Order>)

    @Update
    suspend fun updateOrder(order: Order)

    @Delete
    suspend fun deleteOrder(order: Order)

    @Query("SELECT * FROM orders WHERE deliveryStatus NOT IN ('DELIVERED','CANCELLED','RETURNED') AND officeId = :officeId ORDER BY createdAt DESC")
    fun getAllOrders(officeId: Long = 0): LiveData<List<Order>>

    @Query("SELECT * FROM orders WHERE officeId = :officeId ORDER BY createdAt DESC")
    fun getAllOrdersWithDelivered(officeId: Long = 0): LiveData<List<Order>>

    @Query("SELECT * FROM orders WHERE deliveryStatus = 'DELIVERED' AND officeId = :officeId ORDER BY createdAt DESC")
    fun getDeliveredOrdersAll(officeId: Long = 0): LiveData<List<Order>>

    @Query("""
        SELECT * FROM orders WHERE officeId = :officeId ORDER BY 
        CASE deliveryStatus 
            WHEN 'OUT_FOR_DELIVERY' THEN 0
            WHEN 'PREPARING' THEN 1
            WHEN 'PENDING' THEN 2
            WHEN 'DELIVERED' THEN 3
            WHEN 'RETURNED' THEN 4
            WHEN 'CANCELLED' THEN 5
        END ASC, createdAt DESC
    """)
    fun getAllOrdersSortedByDelivery(officeId: Long = 0): LiveData<List<Order>>

    @Query("""SELECT * FROM orders WHERE (
        customerName LIKE '%' || :query || '%' OR
        driverName LIKE '%' || :query || '%' OR
        deliveryAddress LIKE '%' || :query || '%' OR
        orderType LIKE '%' || :query || '%'
    ) AND officeId = :officeId ORDER BY createdAt DESC""")
    fun searchOrders(query: String, officeId: Long = 0): LiveData<List<Order>>

    @Query("SELECT * FROM orders WHERE createdAt BETWEEN :startTime AND :endTime AND officeId = :officeId ORDER BY createdAt DESC")
    fun getOrdersByDateRange(startTime: Long, endTime: Long, officeId: Long = 0): LiveData<List<Order>>

    @Query("""SELECT * FROM orders WHERE createdAt BETWEEN :startTime AND :endTime AND (
        customerName LIKE '%' || :query || '%' OR
        driverName LIKE '%' || :query || '%' OR
        deliveryAddress LIKE '%' || :query || '%' OR
        orderType LIKE '%' || :query || '%'
    ) AND officeId = :officeId ORDER BY createdAt DESC""")
    fun searchOrdersByDateRange(query: String, startTime: Long, endTime: Long, officeId: Long = 0): LiveData<List<Order>>

    // ✅ مُحدَّث: يعتمد على deliveryStatus بدل isDelivered
    @Query("SELECT * FROM orders WHERE deliveryStatus = 'DELIVERED' AND createdAt >= :startTime AND officeId = :officeId ORDER BY createdAt DESC")
    fun getDeliveredOrdersSince(startTime: Long, officeId: Long = 0): LiveData<List<Order>>

    @Query("SELECT SUM(deliveryPrice) FROM orders WHERE deliveryStatus = 'DELIVERED' AND createdAt >= :startTime AND officeId = :officeId")
    fun getTotalDeliveryRevenue(startTime: Long, officeId: Long = 0): LiveData<Double?>

    @Query("""
        SELECT (createdAt / 86400000) as dayBucket,
               COUNT(*) as dayOrders,
               COALESCE(SUM(CASE WHEN deliveryStatus='DELIVERED' THEN deliveryPrice ELSE 0 END), 0) as dayRevenue
        FROM orders WHERE createdAt >= :startTime AND officeId = :officeId
        GROUP BY dayBucket ORDER BY dayBucket ASC
    """)
    fun getDailyStats(startTime: Long, officeId: Long = 0): LiveData<List<DailyStat>>

    @Query("""
        SELECT deliveryStatus,
               COUNT(*) as statusCount
        FROM orders WHERE createdAt >= :startTime AND deliveryStatus IS NOT NULL AND officeId = :officeId
        GROUP BY deliveryStatus ORDER BY statusCount DESC
    """)
    fun getStatusDistribution(startTime: Long, officeId: Long = 0): LiveData<List<StatusStat>>

    @Query("SELECT SUM(purchasePrice) FROM orders WHERE deliveryStatus = 'DELIVERED' AND createdAt >= :startTime AND officeId = :officeId")
    fun getTotalPurchaseRevenue(startTime: Long, officeId: Long = 0): LiveData<Double?>

    @Query("SELECT COUNT(*) FROM orders WHERE deliveryStatus = 'DELIVERED' AND createdAt >= :startTime AND officeId = :officeId")
    fun getDeliveredCount(startTime: Long, officeId: Long = 0): LiveData<Int>

    @Query("SELECT COUNT(*) FROM orders WHERE createdAt >= :startTime AND officeId = :officeId")
    fun getTotalCount(startTime: Long, officeId: Long = 0): LiveData<Int>

    @Query("SELECT driverName, COUNT(*) as orderCount FROM orders WHERE deliveryStatus = 'DELIVERED' AND createdAt >= :startTime AND driverName != '' AND officeId = :officeId GROUP BY driverName ORDER BY orderCount DESC")
    fun getDriverStats(startTime: Long, officeId: Long = 0): LiveData<List<DriverStat>>

    // للـ Workers
    @Query("SELECT COUNT(*) FROM orders WHERE deliveryStatus NOT IN ('DELIVERED', 'CANCELLED', 'RETURNED') AND officeId = :officeId")
    suspend fun getPendingOrdersCountSync(officeId: Long = 0): Int

    @Query("SELECT COUNT(*) FROM orders WHERE createdAt >= :startTime AND officeId = :officeId")
    suspend fun getTotalCountSync(startTime: Long, officeId: Long = 0): Int

    @Query("SELECT COUNT(*) FROM orders WHERE deliveryStatus = 'DELIVERED' AND createdAt >= :startTime AND officeId = :officeId")
    suspend fun getDeliveredCountSync(startTime: Long, officeId: Long = 0): Int

    @Query("SELECT SUM(deliveryPrice) FROM orders WHERE deliveryStatus = 'DELIVERED' AND createdAt >= :startTime AND officeId = :officeId")
    suspend fun getTotalDeliveryRevenueSync(startTime: Long, officeId: Long = 0): Double?

    @Query("SELECT * FROM orders WHERE officeId = :officeId ORDER BY createdAt DESC")
    suspend fun getAllOrdersSync(officeId: Long = 0): List<Order>

    @Query("SELECT COALESCE(MAX(orderNumber), 0) FROM orders WHERE officeId = :officeId")
    suspend fun getLastOrderNumber(officeId: Long = 0): Int

    @Query("""
        SELECT driverName,
               COUNT(*) as totalOrders,
               SUM(CASE WHEN deliveryStatus = 'DELIVERED' THEN 1 ELSE 0 END) as deliveredCount,
               COALESCE(SUM(CASE WHEN deliveryStatus = 'DELIVERED' THEN deliveryPrice ELSE 0 END), 0) as totalRevenue
        FROM orders 
        WHERE driverName != '' AND driverName IS NOT NULL AND officeId = :officeId
        GROUP BY driverName
    """)
    fun getAllDriverAggregatedStats(officeId: Long = 0): LiveData<List<DriverAggregatedStat>>
}

data class DriverStat(
    val driverName: String,
    val orderCount: Int
)

data class DriverAggregatedStat(
    val driverName: String,
    val totalOrders: Int,
    val deliveredCount: Int,
    val totalRevenue: Double
)

data class DailyStat(
    val dayBucket: Long,
    val dayOrders: Int,
    val dayRevenue: Double
)

data class StatusStat(
    val deliveryStatus: String,
    val statusCount: Int
)
