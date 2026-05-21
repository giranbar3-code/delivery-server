package com.delivery.app.ui.drivers

import android.app.Application
import androidx.lifecycle.*
import com.delivery.app.DeliveryApplication
import com.delivery.app.data.OfficeManager
import com.delivery.app.data.model.Driver
import com.delivery.app.data.local.DriverAggregatedStat
import kotlinx.coroutines.launch

class DriverViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DeliveryApplication
    private val repository = app.driverRepository

    val allDrivers: LiveData<List<Driver>> = OfficeManager.currentOfficeId.switchMap { officeId ->
        repository.getAllDrivers(officeId)
    }

    val allDriverStats: LiveData<List<DriverAggregatedStat>> =
        OfficeManager.currentOfficeId.switchMap { officeId ->
            app.repository.getAllDriverAggregatedStats(officeId)
        }

    fun insertDriver(driver: Driver) = viewModelScope.launch {
        repository.insertDriver(driver)
    }

    fun updateDriver(driver: Driver) = viewModelScope.launch {
        repository.updateDriver(driver)
    }

    fun deleteDriver(driver: Driver) = viewModelScope.launch {
        repository.deleteDriver(driver)
    }

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    val filteredDrivers: LiveData<List<Driver>> = allDrivers.switchMap { drivers ->
        _searchQuery.map { query ->
            if (query.isEmpty()) drivers
            else drivers.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.phone.contains(query, ignoreCase = true)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        // Room LiveData auto-refreshes; this is a no-op trigger for UI feedback
    }
}

class DriverViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DriverViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DriverViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
