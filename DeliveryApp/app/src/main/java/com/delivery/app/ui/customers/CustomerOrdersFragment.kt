package com.delivery.app.ui.customers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.delivery.app.databinding.FragmentCustomerOrdersBinding
import com.delivery.app.ui.OrderViewModel
import com.delivery.app.ui.OrderViewModelFactory
import com.delivery.app.ui.archive.OrderAdapter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CustomerOrdersFragment : Fragment() {

    private var _binding: FragmentCustomerOrdersBinding? = null
    private val binding get() = _binding!!

    private val orderViewModel: OrderViewModel by activityViewModels {
        OrderViewModelFactory(requireActivity().application)
    }

    private lateinit var adapter: OrderAdapter
    private var customerName: String = ""
    private val pendingDeletes = mutableMapOf<Long, Job>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomerOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        customerName = arguments?.getString("customerName", "") ?: ""
        binding.tvCustomerName.text = "طلبات $customerName"

        adapter = OrderAdapter(
            onEdit = {},
            onDelete = { order ->
                if (pendingDeletes[order.id]?.isActive == true) return@OrderAdapter
                val job = lifecycleScope.launch {
                    Snackbar.make(binding.root, "تم حذف طلبية #${order.orderNumber}", Snackbar.LENGTH_LONG)
                        .setAction("تراجع") {
                            pendingDeletes[order.id]?.cancel()
                            pendingDeletes.remove(order.id)
                        }
                        .show()
                    delay(4000)
                    if (pendingDeletes[order.id]?.isActive == true) {
                        orderViewModel.deleteOrder(order)
                        pendingDeletes.remove(order.id)
                    }
                }
                pendingDeletes[order.id] = job
            },
            onStatusChange = { order, status -> orderViewModel.setStatus(order, status) },
            onPrint = null,
            onAssignDriver = null
        )
        binding.recyclerOrders.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerOrders.adapter = adapter

        orderViewModel.setSearchQuery(customerName)
        orderViewModel.filteredOrders.observe(viewLifecycleOwner) { orders ->
            adapter.submitList(orders)
            binding.tvEmpty.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        orderViewModel.setSearchQuery("")
        pendingDeletes.values.forEach { it.cancel() }
        pendingDeletes.clear()
        _binding = null
    }
}
