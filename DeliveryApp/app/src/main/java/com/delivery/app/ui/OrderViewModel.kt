package com.delivery.app.ui

import android.app.Application
import androidx.lifecycle.*
import com.delivery.app.DeliveryApplication
import com.delivery.app.data.OfficeManager
import com.delivery.app.data.local.DriverStat
import com.delivery.app.data.local.StatusHistoryDao
import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.data.model.Order
import com.delivery.app.data.model.StatusHistory
import com.delivery.app.data.repository.OrderRepository
import kotlinx.coroutines.launch
import java.util.Calendar

data class ArchiveFilter(
    val query: String = "",
    val startTime: Long = 0L,
    val endTime: Long = Long.MAX_VALUE,
    val sortByDelivery: Boolean = false,
    val tabPosition: Int = 0
)

class OrderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: OrderRepository =
        (application as DeliveryApplication).repository
    private val statusHistoryDao: StatusHistoryDao =
        (application as DeliveryApplication).statusHistoryDao

    // ===== فلتر الأرشيف =====
    private val _archiveFilter = MutableLiveData(ArchiveFilter())
    val archiveFilter: LiveData<ArchiveFilter> = _archiveFilter

    val filteredOrders: LiveData<List<Order>> = OfficeManager.currentOfficeId.switchMap { officeId ->
        _archiveFilter.switchMap { filter ->
            val hasQuery = filter.query.isNotEmpty()
            val hasDate = filter.startTime > 0L || filter.endTime < Long.MAX_VALUE
            when {
                hasQuery && hasDate ->
                    repository.searchOrdersByDateRange(
                        filter.query, filter.startTime, filter.endTime, officeId
                    )
                hasQuery -> repository.searchOrders(filter.query, officeId)
                hasDate -> repository.getOrdersByDateRange(filter.startTime, filter.endTime, officeId)
                filter.tabPosition == 2 -> repository.getDeliveredOrdersAll(officeId)
                filter.sortByDelivery -> repository.getAllOrdersSortedByDelivery(officeId)
                else -> repository.getAllOrders(officeId)
            }
        }
    }

    val allOrders: LiveData<List<Order>> = OfficeManager.currentOfficeId.switchMap { officeId ->
        repository.getAllOrdersWithDelivered(officeId)
    }

    fun setSearchQuery(query: String) {
        _archiveFilter.value = _archiveFilter.value?.copy(query = query)
    }

    fun setDateRange(start: Long, end: Long) {
        _archiveFilter.value = _archiveFilter.value?.copy(
            startTime = start, endTime = end
        )
    }

    fun clearDateFilter() {
        _archiveFilter.value = _archiveFilter.value?.copy(
            startTime = 0L, endTime = Long.MAX_VALUE
        )
    }

    fun setSortByDelivery(v: Boolean) {
        _archiveFilter.value = _archiveFilter.value?.copy(sortByDelivery = v)
    }

    fun setTabPosition(position: Int) {
        _archiveFilter.value = _archiveFilter.value?.copy(tabPosition = position)
    }

    val sortByDelivery: LiveData<Boolean> = _archiveFilter.map { it.sortByDelivery }
    val hasDateFilter: LiveData<Boolean> = _archiveFilter.map {
        it.startTime > 0L || it.endTime < Long.MAX_VALUE
    }

    // ===== إحصائيات =====
    private val _statsPeriod = MutableLiveData(0)
    val statsPeriod: LiveData<Int> = _statsPeriod

    private val _officeAndPeriod = OfficeManager.currentOfficeId.switchMap { officeId ->
        _statsPeriod.map { period -> officeId to period }
    }

    val statsStartTime: LiveData<Long> = _statsPeriod.map { getStartTime(it) }

    val deliveredOrders: LiveData<List<Order>> =
        _officeAndPeriod.switchMap { (officeId, period) ->
            repository.getDeliveredOrdersSince(getStartTime(period), officeId)
        }

    val deliveredCount: LiveData<Int> =
        _officeAndPeriod.switchMap { (officeId, period) ->
            repository.getDeliveredCount(getStartTime(period), officeId)
        }

    val totalCount: LiveData<Int> =
        _officeAndPeriod.switchMap { (officeId, period) ->
            repository.getTotalCount(getStartTime(period), officeId)
        }

    val totalRevenue: LiveData<Double?> =
        _officeAndPeriod.switchMap { (officeId, period) ->
            repository.getTotalDeliveryRevenue(getStartTime(period), officeId)
        }

    val totalPurchaseRevenue: LiveData<Double?> =
        _officeAndPeriod.switchMap { (officeId, period) ->
            repository.getTotalPurchaseRevenue(getStartTime(period), officeId)
        }

    val driverStats: LiveData<List<DriverStat>> =
        _officeAndPeriod.switchMap { (officeId, period) ->
            repository.getDriverStats(getStartTime(period), officeId)
        }

    val dailyStats: LiveData<List<com.delivery.app.data.local.DailyStat>> =
        _officeAndPeriod.switchMap { (officeId, period) ->
            repository.getDailyStats(getStartTime(period), officeId)
        }

    val statusDistribution: LiveData<List<com.delivery.app.data.local.StatusStat>> =
        _officeAndPeriod.switchMap { (officeId, period) ->
            repository.getStatusDistribution(getStartTime(period), officeId)
        }

    fun setStatsPeriod(period: Int) { _statsPeriod.value = period }

    // ===== CRUD =====
    fun insertOrder(order: Order) = viewModelScope.launch {
        repository.getLastOrderNumber().onSuccess { lastNumber ->
            repository.insertOrder(order.copy(orderNumber = lastNumber + 1))
        }
    }

    fun updateOrder(order: Order) = viewModelScope.launch {
        repository.updateOrder(order)
    }

    fun deleteOrder(order: Order) = viewModelScope.launch {
        repository.deleteOrder(order)
    }

    // ✅ دورة الحالات: PENDING → PREPARING → OUT_FOR_DELIVERY → DELIVERED → RETURNED / CANCELLED
    fun cycleStatus(order: Order) = viewModelScope.launch {
        val next = when (order.statusEnum) {
            DeliveryStatus.PENDING        -> DeliveryStatus.PREPARING
            DeliveryStatus.PREPARING      -> DeliveryStatus.OUT_FOR_DELIVERY
            DeliveryStatus.OUT_FOR_DELIVERY -> DeliveryStatus.DELIVERED
            DeliveryStatus.DELIVERED      -> DeliveryStatus.PENDING
            DeliveryStatus.RETURNED       -> DeliveryStatus.PENDING
            DeliveryStatus.CANCELLED      -> DeliveryStatus.PENDING
        }
        val updated = order.copy(deliveryStatus = next.name)
        repository.updateOrder(updated)
        statusHistoryDao.insert(StatusHistory(
            orderId = order.id,
            fromStatus = order.deliveryStatus,
            toStatus = next.name
        ))
    }

    // ✅ تعيين حالة محددة مباشرة (من قائمة الحالات)
    fun setStatus(order: Order, status: DeliveryStatus) = viewModelScope.launch {
        if (order.deliveryStatus == status.name) return@launch
        val updated = order.copy(deliveryStatus = status.name)
        repository.updateOrder(updated)
        statusHistoryDao.insert(StatusHistory(
            orderId = order.id,
            fromStatus = order.deliveryStatus,
            toStatus = status.name
        ))
    }

    fun importOrders(orders: List<Order>) = viewModelScope.launch {
        repository.insertOrders(orders)
    }

    private fun getStartTime(period: Int): Long {
        val cal = Calendar.getInstance()
        when (period) {
            0 -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            1 -> cal.add(Calendar.WEEK_OF_YEAR, -1)
            2 -> cal.add(Calendar.MONTH, -1)
        }
        return cal.timeInMillis
    }
}

class OrderViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OrderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OrderViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
