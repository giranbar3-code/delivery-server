package com.delivery.app.ui.customers

import android.app.Application
import androidx.lifecycle.*
import com.delivery.app.DeliveryApplication
import com.delivery.app.data.OfficeManager
import com.delivery.app.data.local.CustomerAggregatedStat
import com.delivery.app.data.model.Customer
import kotlinx.coroutines.launch

class CustomerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DeliveryApplication
    private val repository = app.customerRepository

    val allCustomers: LiveData<List<Customer>> = OfficeManager.currentOfficeId.switchMap { officeId ->
        repository.getAllCustomers(officeId)
    }

    val allCustomerStats: LiveData<List<CustomerAggregatedStat>> =
        OfficeManager.currentOfficeId.switchMap { officeId ->
            repository.getAllCustomerStats(officeId)
        }

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    val filteredCustomers: LiveData<List<Customer>> = allCustomers.switchMap { customers ->
        _searchQuery.map { query ->
            if (query.isEmpty()) customers
            else customers.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.phone.contains(query, ignoreCase = true)
            }
        }
    }

    fun insertCustomer(customer: Customer) = viewModelScope.launch {
        repository.insertCustomer(customer)
    }

    fun deleteCustomer(customer: Customer) = viewModelScope.launch {
        repository.deleteCustomer(customer)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        // Room LiveData auto-refreshes; this is a no-op trigger for UI feedback
    }

    fun getOrderCount(name: String): LiveData<Int> =
        OfficeManager.currentOfficeId.switchMap { officeId ->
            repository.getOrderCount(name, officeId)
        }

    fun getDeliveredCount(name: String): LiveData<Int> =
        OfficeManager.currentOfficeId.switchMap { officeId ->
            repository.getDeliveredCount(name, officeId)
        }
}

class CustomerViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CustomerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CustomerViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
