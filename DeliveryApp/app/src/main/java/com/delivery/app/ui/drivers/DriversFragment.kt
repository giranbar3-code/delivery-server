package com.delivery.app.ui.drivers

import android.app.AlertDialog
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
import com.delivery.app.data.model.Driver
import com.delivery.app.databinding.FragmentDriversBinding
import com.delivery.app.utils.PhoneValidator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DriversFragment : Fragment() {

    private var _binding: FragmentDriversBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DriverViewModel by viewModels {
        DriverViewModelFactory(requireActivity().application)
    }

    private lateinit var adapter: DriverAdapter
    private val pendingDriverDeletes = mutableMapOf<Long, Job>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriversBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DriverAdapter(
            onEdit = { driver -> showEditDialog(driver) },
            onDelete = { driver ->
                if (pendingDriverDeletes[driver.id]?.isActive == true) return@DriverAdapter
                val job = lifecycleScope.launch {
                    Snackbar.make(binding.root, "تم حذف السائق ${driver.name}", Snackbar.LENGTH_LONG)
                        .setAction("تراجع") {
                            pendingDriverDeletes[driver.id]?.cancel()
                            pendingDriverDeletes.remove(driver.id)
                        }
                        .show()
                    delay(4000)
                    if (pendingDriverDeletes[driver.id]?.isActive == true) {
                        viewModel.deleteDriver(driver)
                        pendingDriverDeletes.remove(driver.id)
                    }
                }
                pendingDriverDeletes[driver.id] = job
            }
        )

        binding.recyclerDrivers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDrivers.adapter = adapter

        binding.swipeRefreshDrivers.setOnRefreshListener {
            viewModel.refresh()
            lifecycleScope.launch {
                delay(2000)
                binding.swipeRefreshDrivers.isRefreshing = false
            }
        }

        binding.etSearchDriver.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                viewModel.setSearchQuery(s.toString().trim())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        viewModel.filteredDrivers.observe(viewLifecycleOwner) { drivers ->
            adapter.submitList(drivers)
            binding.tvEmptyDrivers.visibility =
                if (drivers.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.allDriverStats.observe(viewLifecycleOwner) { stats ->
            adapter.updateStats(stats)
        }

        binding.btnShowAddDriver.setOnClickListener {
            binding.cardAddDriver.visibility = View.VISIBLE
            binding.etDriverName.requestFocus()
        }

        binding.btnCancelAddDriver.setOnClickListener {
            binding.cardAddDriver.visibility = View.GONE
            binding.etDriverName.text?.clear()
            binding.etDriverPhone.text?.clear()
        }

        binding.btnAddDriver.setOnClickListener {
            val name = binding.etDriverName.text.toString().trim()
            val phone = binding.etDriverPhone.text.toString().trim()

            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "يرجى إدخال اسم السائق", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.insertDriver(
                Driver(name = name, phone = phone, officeId = OfficeManager.currentOfficeId.value ?: 0L)
            )
            binding.etDriverName.text?.clear()
            binding.etDriverPhone.text?.clear()
            binding.cardAddDriver.visibility = View.GONE
            Toast.makeText(requireContext(), "تم إضافة السائق", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDialog(driver: Driver) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(driver.name)
            hint = "اسم السائق"
            setSelection(text.length)
        }
        val inputPhone = android.widget.EditText(requireContext()).apply {
            setText(driver.phone)
            hint = "رقم الهاتف"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(input)
            addView(inputPhone)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("تعديل السائق")
            .setView(layout)
            .setPositiveButton("حفظ") { dialog: android.content.DialogInterface, _ ->
                val newName = input.text.toString().trim()
                val newPhone = inputPhone.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val phoneError = PhoneValidator.getErrorMessage(newPhone)
                    if (phoneError.isNotEmpty() && newPhone.isNotEmpty()) {
                        Toast.makeText(requireContext(), phoneError, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        return@setPositiveButton
                    }
                    viewModel.updateDriver(driver.copy(name = newName, phone = newPhone))
                    Toast.makeText(requireContext(), "تم التعديل", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء") { dialog: android.content.DialogInterface, _ -> dialog.dismiss() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingDriverDeletes.values.forEach { it.cancel() }
        pendingDriverDeletes.clear()
        _binding = null
    }
}