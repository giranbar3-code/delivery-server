package com.delivery.app.ui.customers

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.delivery.app.R
import com.delivery.app.data.OfficeManager
import com.delivery.app.databinding.FragmentCustomersBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CustomersFragment : Fragment() {

    private var _binding: FragmentCustomersBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CustomerViewModel by viewModels {
        CustomerViewModelFactory(requireActivity().application)
    }

    private lateinit var adapter: CustomerAdapter
    private val pendingCustomerDeletes = mutableMapOf<Long, Job>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = CustomerAdapter(
            onCustomerClick = { customer ->
                val bundle = Bundle().apply { putString("customerName", customer.name) }
                findNavController().navigate(R.id.customerOrdersFragment, bundle)
            },
            onDelete = { customer ->
                if (pendingCustomerDeletes[customer.id]?.isActive == true) return@CustomerAdapter
                val job = lifecycleScope.launch {
                    Snackbar.make(binding.root, "تم حذف العميل ${customer.name}", Snackbar.LENGTH_LONG)
                        .setAction("تراجع") {
                            pendingCustomerDeletes[customer.id]?.cancel()
                            pendingCustomerDeletes.remove(customer.id)
                        }
                        .show()
                    delay(4000)
                    if (pendingCustomerDeletes[customer.id]?.isActive == true) {
                        viewModel.deleteCustomer(customer)
                        pendingCustomerDeletes.remove(customer.id)
                    }
                }
                pendingCustomerDeletes[customer.id] = job
            }
        )

        binding.recyclerCustomers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCustomers.adapter = adapter

        binding.swipeRefreshCustomers.setOnRefreshListener {
            viewModel.refresh()
            lifecycleScope.launch {
                delay(2000)
                binding.swipeRefreshCustomers.isRefreshing = false
            }
        }

        binding.etSearchCustomer.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                viewModel.setSearchQuery(s.toString().trim())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        viewModel.filteredCustomers.observe(viewLifecycleOwner) { customers ->
            adapter.submitList(customers)
            binding.tvEmptyCustomers.visibility =
                if (customers.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.allCustomerStats.observe(viewLifecycleOwner) { stats ->
            val statsByName = stats.associateBy { it.customerName }
            val ordersMap = mutableMapOf<Long, Int>()
            val deliveredMap = mutableMapOf<Long, Int>()
            adapter.currentList.forEach { c ->
                val s = statsByName[c.name]
                ordersMap[c.id] = s?.totalOrders ?: 0
                deliveredMap[c.id] = s?.deliveredCount ?: 0
            }
            adapter.updateCounts(ordersMap, deliveredMap)
        }

        binding.btnShowAddCustomer.setOnClickListener {
            binding.cardAddCustomer.visibility = View.VISIBLE
            binding.etCustomerName.requestFocus()
        }

        binding.btnCancelAddCustomer.setOnClickListener {
            binding.cardAddCustomer.visibility = View.GONE
            clearAddFields()
        }

        binding.btnAddCustomer.setOnClickListener {
            val name = binding.etCustomerName.text.toString().trim()
            val phone = binding.etCustomerPhone.text.toString().trim()
            val address = binding.etCustomerAddress.text.toString().trim()
            val notes = binding.etCustomerNotes.text.toString().trim()

            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "يرجى إدخال اسم العميل", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.insertCustomer(
                com.delivery.app.data.model.Customer(
                    name = name,
                    phone = phone,
                    address = address,
                    notes = notes,
                    officeId = OfficeManager.currentOfficeId.value ?: 0L
                )
            )
            clearAddFields()
            binding.cardAddCustomer.visibility = View.GONE
            Toast.makeText(requireContext(), "تم إضافة العميل", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAddFields() {
        binding.etCustomerName.text?.clear()
        binding.etCustomerPhone.text?.clear()
        binding.etCustomerAddress.text?.clear()
        binding.etCustomerNotes.text?.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingCustomerDeletes.values.forEach { it.cancel() }
        pendingCustomerDeletes.clear()
        _binding = null
    }
}
