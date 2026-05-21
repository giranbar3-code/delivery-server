package com.delivery.app.ui.add

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.delivery.app.R
import com.delivery.app.data.OfficeManager
import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.data.model.Order
import com.delivery.app.databinding.FragmentAddOrderBinding
import com.delivery.app.ui.OrderViewModel
import com.delivery.app.ui.OrderViewModelFactory
import com.delivery.app.data.model.Customer
import com.delivery.app.ui.customers.CustomerViewModel
import com.delivery.app.ui.customers.CustomerViewModelFactory
import com.delivery.app.ui.drivers.DriverViewModel
import com.delivery.app.ui.drivers.DriverViewModelFactory
import com.delivery.app.utils.PhoneValidator
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject

class AddOrderFragment : Fragment() {

    private var _binding: FragmentAddOrderBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OrderViewModel by activityViewModels {
        OrderViewModelFactory(requireActivity().application)
    }

    private val driverViewModel: DriverViewModel by activityViewModels {
        DriverViewModelFactory(requireActivity().application)
    }

    private val customerViewModel: CustomerViewModel by activityViewModels {
        CustomerViewModelFactory(requireActivity().application)
    }

    private var editingOrder: Order? = null
    private var itemRowCount = 0
    private var selectedDriverId: Long = 0
    private val driverList = mutableListOf<Pair<Long, String>>()
    private val customerList = mutableListOf<Customer>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddOrderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDriverDropdown()
        setupCustomerAutoComplete()
        setupStatusDropdown()
        setupMapButton()
        setupItemsSection()

        arguments?.getLong("orderId", -1L)?.takeIf { it != -1L }?.let { id ->
            viewModel.allOrders.observe(viewLifecycleOwner) { orders ->
                orders.find { it.id == id }?.let { order ->
                    if (editingOrder == null) {
                        editingOrder = order
                        populateFields(order)
                    }
                }
            }
        }

        binding.btnSave.setOnClickListener { saveOrder() }
        binding.btnClear.setOnClickListener { clearFields() }
    }

    private fun setupItemsSection() {
        binding.btnAddItem.setOnClickListener { addItemRow("", 1) }
        addItemRow("", 1)
    }

    private fun addItemRow(name: String = "", qty: Int = 1) {
        itemRowCount++
        val inflater = LayoutInflater.from(requireContext())
        val row = inflater.inflate(R.layout.item_order_row, binding.itemsContainer, false) as LinearLayout

        val etName = row.findViewById<EditText>(R.id.et_item_name)
        val etQty = row.findViewById<EditText>(R.id.et_item_qty)
        val btnRemove = row.findViewById<MaterialButton>(R.id.btn_remove_item)

        etName.setText(name)
        etQty.setText(if (qty > 0) qty.toString() else "1")

        btnRemove.setOnClickListener {
            binding.itemsContainer.removeView(row)
        }

        binding.itemsContainer.addView(row)
    }

    private fun getItemsFromRows(): List<Pair<String, Int>> {
        val items = mutableListOf<Pair<String, Int>>()
        for (i in 0 until binding.itemsContainer.childCount) {
            val row = binding.itemsContainer.getChildAt(i) as? LinearLayout ?: continue
            val name = row.findViewById<EditText>(R.id.et_item_name).text.toString().trim()
            val qtyStr = row.findViewById<EditText>(R.id.et_item_qty).text.toString().trim()
            val qty = qtyStr.toIntOrNull() ?: 1
            if (name.isNotEmpty()) {
                items.add(name to qty)
            }
        }
        return items
    }

    private fun itemsToJson(items: List<Pair<String, Int>>): String {
        val arr = JSONArray()
        items.forEach { (name, qty) ->
            arr.put(JSONObject().apply {
                put("name", name)
                put("quantity", qty)
            })
        }
        return arr.toString()
    }

    private fun itemsFromJson(json: String): List<Pair<String, Int>> {
        val items = mutableListOf<Pair<String, Int>>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "")
                val qty = obj.optInt("quantity", 1)
                if (name.isNotEmpty()) items.add(name to qty)
            }
        } catch (_: Exception) {}
        return items
    }

    private fun setupStatusDropdown() {
        val statuses = DeliveryStatus.values()
        val labels = statuses.map { "${it.emoji} ${it.label}" }.toTypedArray()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        val autoComplete = binding.etDeliveryStatus
        autoComplete.setAdapter(adapter)
        autoComplete.setOnItemClickListener { _, _, position, _ ->
            editingOrder?.let { order ->
                viewModel.setStatus(order, statuses[position])
            }
        }
    }

    private fun setupMapButton() {
        binding.btnOpenMap.setOnClickListener {
            val url = binding.etLocationUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "أدخل رابط الموقع أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openLocationInMaps(url)
        }
    }

    private fun openLocationInMaps(url: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme !in listOf("http", "https", "geo")) {
                Toast.makeText(requireContext(), "رابط غير صالح", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "تعذّر فتح الرابط", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCustomerAutoComplete() {
        customerViewModel.allCustomers.observe(viewLifecycleOwner) { customers ->
            customerList.clear()
            customerList.addAll(customers)
            val names = customers.map { "${it.name} - ${it.phone}" }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
            val autoComplete = binding.etCustomerName as? AutoCompleteTextView
            autoComplete?.setAdapter(adapter)
            autoComplete?.threshold = 1
            autoComplete?.setOnItemClickListener { _, _, position, _ ->
                val customer = customers.getOrNull(position)
                if (customer != null) {
                    binding.etCustomerName.setText(customer.name, false)
                    binding.etCustomerPhone.setText(customer.phone)
                    binding.etAddress.setText(customer.address)
                }
            }
        }
    }

    private fun setupDriverDropdown() {
        driverViewModel.allDrivers.observe(viewLifecycleOwner) { drivers ->
            driverList.clear()
            driverList.add(0L to "-- بدون سائق --")
            driverList.addAll(drivers.map { it.id to it.name })
            val names = driverList.map { it.second }
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                names
            )
            val autoComplete = binding.etDriverName as? AutoCompleteTextView
            autoComplete?.setAdapter(adapter)
            autoComplete?.threshold = 1
            autoComplete?.setOnItemClickListener { _, _, position, _ ->
                selectedDriverId = driverList.getOrNull(position)?.first ?: 0L
                if (position == 0) {
                    autoComplete.setText("")
                    selectedDriverId = 0L
                }
            }
        }
    }

    private fun populateFields(order: Order) {
        binding.etCustomerName.setText(order.customerName)
        binding.etCustomerPhone.setText(order.customerPhone)
        binding.etAddress.setText(order.deliveryAddress)
        binding.etLocationUrl.setText(order.locationUrl)
        binding.etDeliveryStatus.setText("${order.statusEnum.emoji} ${order.statusEnum.label}", false)
        binding.etDriverName.setText(order.driverName)
        selectedDriverId = order.driverId
        binding.etPurchasePrice.setText(order.purchasePrice.toString())
        binding.etDeliveryPrice.setText(order.deliveryPrice.toString())
        binding.etNotes.setText(order.notes)
        binding.btnSave.text = "تحديث الطلبية"
        binding.tvOrderNumber.text = "طلبية رقم #${order.orderNumber}"
        binding.tvOrderNumber.visibility = View.VISIBLE

        binding.itemsContainer.removeAllViews()
        val items = itemsFromJson(order.items)
        if (items.isNotEmpty()) {
            items.forEach { (name, qty) -> addItemRow(name, qty) }
        } else {
            addItemRow(order.orderType, order.quantity)
        }

        if (order.isWebOrder) {
            lockWebOrderFields()
        }
    }

    private fun lockWebOrderFields() {
        binding.etCustomerName.isEnabled = false
        binding.etCustomerPhone.isEnabled = false
        binding.etAddress.isEnabled = false
        binding.etLocationUrl.isEnabled = false
        binding.btnAddItem.visibility = View.GONE

        for (i in 0 until binding.itemsContainer.childCount) {
            val row = binding.itemsContainer.getChildAt(i) as? LinearLayout ?: continue
            row.alpha = 0.5f
            row.findViewById<View>(R.id.btn_remove_item)?.isEnabled = false
            row.findViewById<View>(R.id.et_item_name)?.isEnabled = false
            row.findViewById<View>(R.id.et_item_qty)?.isEnabled = false
        }
    }

    private fun restoreSaveButton() {
        binding.btnSave.isEnabled = true
        binding.btnSave.text = if (editingOrder != null) "تحديث الطلبية" else "حفظ الطلبية"
    }

    private fun saveOrder() {
        binding.btnSave.isEnabled = false
        binding.btnSave.text = "جارٍ الحفظ..."

        val existing = editingOrder
        val isWeb = existing?.isWebOrder == true

        val customerName = if (isWeb) existing!!.customerName else binding.etCustomerName.text.toString().trim()
        val customerPhone = if (isWeb) existing!!.customerPhone else binding.etCustomerPhone.text.toString().trim()
        val address = if (isWeb) existing!!.deliveryAddress else binding.etAddress.text.toString().trim()
        val locationUrl = if (isWeb) existing!!.locationUrl else binding.etLocationUrl.text.toString().trim()
        val driverName = binding.etDriverName.text.toString().trim()
        val purchasePriceStr = binding.etPurchasePrice.text.toString().trim()
        val deliveryPriceStr = binding.etDeliveryPrice.text.toString().trim()

        val items = if (isWeb) {
            emptyList()
        } else {
            getItemsFromRows()
        }

        if (!isWeb && (customerName.isEmpty() || customerPhone.isEmpty() || address.isEmpty() ||
            locationUrl.isEmpty() || items.isEmpty() || deliveryPriceStr.isEmpty())
        ) {
            Toast.makeText(requireContext(), "يرجى ملء جميع الحقول المطلوبة بما فيها رابط الموقع", Toast.LENGTH_LONG).show()
            restoreSaveButton()
            return
        }

        if (!isWeb) {
            val phoneError = PhoneValidator.getErrorMessage(customerPhone)
            if (phoneError.isNotEmpty()) {
                Toast.makeText(requireContext(), phoneError, Toast.LENGTH_LONG).show()
                restoreSaveButton()
                return
            }
        }

        val cleanPhone = if (isWeb) customerPhone else PhoneValidator.cleanPhone(customerPhone)

        val purchasePrice = if (purchasePriceStr.isEmpty()) 0.0 else purchasePriceStr.toDoubleOrNull() ?: run {
            Toast.makeText(requireContext(), "سعر الشراء غير صحيح", Toast.LENGTH_SHORT).show()
            restoreSaveButton()
            return
        }
        if (purchasePrice < 0) {
            Toast.makeText(requireContext(), "سعر الشراء لا يمكن أن يكون سالباً", Toast.LENGTH_SHORT).show()
            restoreSaveButton()
            return
        }
        val deliveryPrice = deliveryPriceStr.toDoubleOrNull() ?: run {
            Toast.makeText(requireContext(), "سعر التوصيل غير صحيح", Toast.LENGTH_SHORT).show()
            restoreSaveButton()
            return
        }
        if (deliveryPrice < 0) {
            Toast.makeText(requireContext(), "سعر التوصيل لا يمكن أن يكون سالباً", Toast.LENGTH_SHORT).show()
            restoreSaveButton()
            return
        }

        val orderType = if (isWeb) existing!!.orderType else items.joinToString("، ") { it.first }
        val quantity = if (isWeb) existing!!.quantity else items.sumOf { it.second }
        val itemsJson = if (isWeb) existing!!.items else itemsToJson(items)

        val selectedStatusText = binding.etDeliveryStatus.text.toString().trim()
        val newStatus = if (selectedStatusText.isNotEmpty()) {
            DeliveryStatus.values().find { "${it.emoji} ${it.label}" == selectedStatusText || it.label == selectedStatusText }
                ?.name ?: DeliveryStatus.PENDING.name
        } else {
            DeliveryStatus.PENDING.name
        }

        val notes = binding.etNotes.text.toString().trim()

        if (existing != null) {
            viewModel.updateOrder(
                existing.copy(
                    customerName    = customerName,
                    customerPhone   = cleanPhone,
                    orderType       = orderType,
                    quantity        = quantity,
                    items           = itemsJson,
                    deliveryAddress = address,
                    locationUrl     = locationUrl,
                    deliveryStatus  = newStatus,
                    driverName      = driverName,
                    driverId        = selectedDriverId,
                    purchasePrice   = purchasePrice,
                    deliveryPrice   = deliveryPrice,
                    notes           = notes
                )
            )
            Toast.makeText(requireContext(), "تم تحديث الطلبية", Toast.LENGTH_SHORT).show()
            updateCustomerIfExists(customerName, cleanPhone, address)
            binding.btnSave.isEnabled = true
            binding.btnSave.text = "تحديث الطلبية"
            findNavController().popBackStack()
        } else {
            viewModel.insertOrder(
                Order(
                    customerName    = customerName,
                    customerPhone   = cleanPhone,
                    orderType       = orderType,
                    quantity        = quantity,
                    items           = itemsJson,
                    deliveryAddress = address,
                    locationUrl     = locationUrl,
                    deliveryStatus  = newStatus,
                    driverName      = driverName,
                    driverId        = selectedDriverId,
                    purchasePrice   = purchasePrice,
                    deliveryPrice   = deliveryPrice,
                    notes           = notes,
                    officeId        = OfficeManager.currentOfficeId.value ?: 0L
                )
            )
            Toast.makeText(requireContext(), "تم حفظ الطلبية", Toast.LENGTH_SHORT).show()
            saveCustomerIfNew(customerName, cleanPhone, address)
            clearFields()
            findNavController().popBackStack()
        }
    }

    private fun saveCustomerIfNew(name: String, phone: String, address: String) {
        if (name.isEmpty()) return
        val exists = customerList.any { it.name == name }
        if (!exists) {
            customerViewModel.insertCustomer(Customer(name = name, phone = phone, address = address, officeId = OfficeManager.currentOfficeId.value ?: 0L))
        }
    }

    private fun updateCustomerIfExists(name: String, phone: String, address: String) {
        if (name.isEmpty()) return
        val existing = customerList.find { it.name == name } ?: return
        val updated = existing.copy(phone = phone, address = address)
        if (updated != existing) {
            customerViewModel.insertCustomer(updated)
        }
    }

    private fun clearFields() {
        editingOrder = null
        selectedDriverId = 0L
        binding.etCustomerName.text?.clear()
        binding.etCustomerPhone.text?.clear()
        binding.etAddress.text?.clear()
        binding.etLocationUrl.text?.clear()
        binding.etDeliveryStatus.setText("")
        binding.etDriverName.setText("")
        binding.etPurchasePrice.text?.clear()
        binding.etDeliveryPrice.text?.clear()
        binding.etNotes.text?.clear()
        binding.btnSave.text = "حفظ الطلبية"
        binding.tvOrderNumber.visibility = View.GONE
        binding.itemsContainer.removeAllViews()
        addItemRow("", 1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
