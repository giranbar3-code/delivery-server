package com.delivery.app.ui.archive

import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.delivery.app.DeliveryApplication
import com.delivery.app.R
import com.delivery.app.data.model.Driver
import com.delivery.app.data.model.Order
import com.delivery.app.data.repository.SyncManager
import com.delivery.app.databinding.FragmentArchiveBinding
import com.delivery.app.ui.OrderViewModel
import com.delivery.app.ui.OrderViewModelFactory
import com.delivery.app.ui.drivers.DriverViewModel
import com.delivery.app.ui.drivers.DriverViewModelFactory
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class ArchiveFragment : Fragment() {

    private var _binding: FragmentArchiveBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OrderViewModel by activityViewModels {
        OrderViewModelFactory(requireActivity().application)
    }

    private val driverViewModel: DriverViewModel by activityViewModels {
        DriverViewModelFactory(requireActivity().application)
    }

    private lateinit var adapter: OrderAdapter
    private var currentTab = 1
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var allOrders = listOf<Order>()
    private var driverList = listOf<Driver>()
    private val pendingDeletes = mutableMapOf<Long, Job>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()
        setupTabs()
        setupDatePicker()
        setupSelectionMode()
        setupSwipeRefresh()
        setupAddButton()
        observeOrders()
        autoSync()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val app = requireContext().applicationContext as DeliveryApplication
            lifecycleScope.launch {
                try {
                    withTimeout(15_000) {
                        val result = withContext(Dispatchers.IO) {
                            app.syncManager.syncNewOrders()
                        }
                        if (result is SyncManager.SyncResult.Success && result.count > 0) {
                            Toast.makeText(requireContext(), "🆕 وصلت ${result.count} طلبية جديدة!", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Toast.makeText(requireContext(), "❌ انتهت مهلة الاتصال", Toast.LENGTH_SHORT).show()
                } finally {
                    if (_binding != null) {
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun autoSync() {
        lifecycleScope.launch {
            val app = requireContext().applicationContext as DeliveryApplication
            val result = withContext(Dispatchers.IO) {
                app.syncManager.syncNewOrders()
            }
            if (result is SyncManager.SyncResult.Success && result.count > 0) {
                Toast.makeText(requireContext(), "🆕 وصلت ${result.count} طلبية جديدة!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = OrderAdapter(
            onEdit = { order ->
                val bundle = Bundle().apply { putLong("orderId", order.id) }
                findNavController().navigate(R.id.action_archive_to_add, bundle)
            },
            onDelete = { order -> deleteWithUndo(order) },
            onStatusChange = { order, newStatus ->
                viewModel.setStatus(order, newStatus)
            },
            onPrint = { order -> printReceipt(order) },
            onAssignDriver = { order -> showDriverSelectionDialog(order) }
        )
        binding.recyclerOrders.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerOrders.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s.toString().trim())
                binding.recyclerOrders.smoothScrollToPosition(0)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupTabs() {
        binding.tabStatus.removeAllTabs()
        binding.tabStatus.addTab(binding.tabStatus.newTab().setText("الكل"))
        binding.tabStatus.addTab(binding.tabStatus.newTab().setText("نشطة"))
        binding.tabStatus.addTab(binding.tabStatus.newTab().setText("تم التوصيل"))
        binding.tabStatus.getTabAt(1)?.select()
        viewModel.setTabPosition(1)
        binding.tabStatus.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 1
                viewModel.setTabPosition(currentTab)
                binding.recyclerOrders.smoothScrollToPosition(0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupDatePicker() {
        binding.btnDateFilter.setOnClickListener {
            val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("اختر نطاق التاريخ")
                .build()

            dateRangePicker.addOnPositiveButtonClickListener { selection ->
                val startTime = selection.first ?: return@addOnPositiveButtonClickListener
                val endTime = (selection.second ?: startTime) + (24 * 60 * 60 * 1000L) - 1

                viewModel.setDateRange(startTime, endTime)

                val startStr = dateFormat.format(Date(startTime))
                val endStr = dateFormat.format(Date(endTime))
                binding.btnDateFilter.text = "$startStr - $endStr"
                binding.btnClearDate.visibility = View.VISIBLE
            }

            dateRangePicker.show(parentFragmentManager, "DATE_RANGE_PICKER")
        }

        binding.btnClearDate.setOnClickListener {
            viewModel.clearDateFilter()
            binding.btnDateFilter.text = "📅 التاريخ"
            binding.btnClearDate.visibility = View.GONE
        }

        viewModel.hasDateFilter.observe(viewLifecycleOwner) { hasFilter ->
            binding.btnClearDate.visibility = if (hasFilter) View.VISIBLE else View.GONE
        }
    }

    private fun setupSelectionMode() {
        binding.btnSelectMode.setOnClickListener {
            adapter.selectionMode = !adapter.selectionMode
            if (!adapter.selectionMode) adapter.clearSelection()
            updateSelectionUI()
        }

        binding.btnBulkDelete.setOnClickListener {
            val count = adapter.selectedOrders.size
            if (count == 0) {
                Toast.makeText(requireContext(), "اختر طلبيات أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("حذف $count طلبية")
                .setMessage("هل أنت متأكد من حذف $count طلبية؟\nلا يمكن التراجع.")
                .setPositiveButton("حذف") { _, _ ->
                    val selected = adapter.selectedOrders.toList()
                    adapter.clearSelection()
                    updateSelectionUI()
                    selected.forEach { id ->
                        allOrders.find { it.id == id }?.let { order ->
                            deleteWithUndo(order)
                        }
                    }
                    Snackbar.make(binding.root, "جارٍ حذف $count طلبيات...", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun updateSelectionUI() {
        val inSelection = adapter.selectionMode
        binding.btnSelectMode.text = if (inSelection) "✕" else "☐"
        binding.btnBulkDelete.visibility = if (inSelection) View.VISIBLE else View.GONE
    }

    private fun printReceipt(order: Order) {
        val context = requireContext()
        val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as PrintManager

        val itemsText = try {
            val arr = org.json.JSONArray(order.items)
            (0 until arr.length()).joinToString("\n") { i ->
                val item = arr.getJSONObject(i)
                "- ${item.optString("name", "")} ×${item.optInt("quantity", 1)}"
            }
        } catch (e: Exception) { order.orderType }

        val receiptText = buildString {
            appendLine("╔══════════════════════════╗")
            appendLine("║     مكتب التوصيل         ║")
            appendLine("╚══════════════════════════╝")
            appendLine()
            appendLine("الطلبية رقم: #${order.orderNumber}")
            appendLine("التاريخ: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(order.createdAt))}")
            appendLine()
            appendLine("الزبون: ${order.customerName}")
            appendLine("الهاتف: ${order.customerPhone}")
            appendLine("العنوان: ${order.deliveryAddress}")
            appendLine()
            appendLine("المواد:")
            appendLine(itemsText)
            appendLine()
            appendLine("سعر الشراء: ${order.purchasePrice} ل.س")
            appendLine("أجرة التوصيل: ${order.deliveryPrice} ل.س")
            if (order.driverName.isNotEmpty()) {
                appendLine("السائق: ${order.driverName}")
            }
            if (order.notes.isNotEmpty()) {
                appendLine()
                appendLine("ملاحظات: ${order.notes}")
            }
            appendLine()
            appendLine("الحالة: ${order.statusEnum.emoji} ${order.statusEnum.label}")
            appendLine()
            appendLine("══════════════════════════")
            appendLine("شكراً لتعاملكم 🤝")
        }

        val title = "وصل_توصيل_${order.orderNumber}"
        try {
            val docAdapter = object : PrintDocumentAdapter() {
                override fun onWrite(
                    pages: Array<android.print.PageRange>?,
                    destination: android.os.ParcelFileDescriptor?,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: PrintDocumentAdapter.WriteResultCallback?
                ) {
                    val pdfDocument = android.graphics.pdf.PdfDocument()
                    val paint = android.graphics.Paint().apply {
                        textSize = 28f
                        color = android.graphics.Color.BLACK
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(800, 1000, 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas
                    var y = 50f
                    val rightX = canvas.width - 50f
                    receiptText.lines().forEach { line ->
                        if (y > canvas.height - 50) return@forEach
                        canvas.drawText(line, rightX, y, paint)
                        y += paint.textSize + 8f
                    }
                    pdfDocument.finishPage(page)
                    try {
                        destination?.let {
                            java.io.FileOutputStream(it.fileDescriptor).use { fos ->
                                pdfDocument.writeTo(fos)
                            }
                        }
                    } catch (_: Exception) {}
                    pdfDocument.close()
                    callback?.onWriteFinished(arrayOf(android.print.PageRange(0, 0)))
                }

                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: PrintDocumentAdapter.LayoutResultCallback?,
                    metadata: android.os.Bundle?
                ) {
                    callback?.onLayoutFinished(
                        android.print.PrintDocumentInfo.Builder(title)
                            .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(1)
                            .build(), true
                    )
                }
            }
            printManager.print(title, docAdapter, PrintAttributes.Builder().build())
        } catch (e: Exception) {
            Toast.makeText(context, "تعذّرت الطباعة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteWithUndo(order: Order) {
        val existingJob = pendingDeletes[order.id]
        if (existingJob?.isActive == true) return

        val job = lifecycleScope.launch {
            Snackbar.make(binding.root, "تم حذف طلبية #${order.orderNumber}", Snackbar.LENGTH_LONG)
                .setAction("تراجع") {
                    pendingDeletes[order.id]?.cancel()
                    pendingDeletes.remove(order.id)
                }
                .show()

            delay(4000)

            if (isActive) {
                viewModel.deleteOrder(order)
                pendingDeletes.remove(order.id)
            }
        }
        pendingDeletes[order.id] = job
    }

    private fun setupAddButton() {
        binding.btnAddOrder.setOnClickListener {
            findNavController().navigate(R.id.action_archive_to_add)
        }
    }

    private fun observeOrders() {
        viewModel.filteredOrders.observe(viewLifecycleOwner) { orders ->
            allOrders = orders
            applyTabFilter(orders)
        }

        driverViewModel.allDrivers.observe(viewLifecycleOwner) { drivers ->
            driverList = drivers
            adapter.setDriverPhoneMap(drivers.filter { it.phone.isNotEmpty() }.associate { it.name to it.phone })
        }
    }

    private fun applyTabFilter(orders: List<Order>? = null) {
        val sourceOrders = orders ?: return
        adapter.submitList(sourceOrders)
        binding.tvEmpty.visibility = if (sourceOrders.isEmpty()) View.VISIBLE else View.GONE
        val query = viewModel.archiveFilter.value?.query ?: ""
        val hasDate = viewModel.hasDateFilter.value == true
        binding.tvEmpty.text = when {
            query.isNotEmpty() -> "لا توجد نتائج للبحث عن \"$query\""
            hasDate -> "لا توجد طلبيات في هذه الفترة"
            currentTab == 2 -> "لا توجد طلبات تم توصيلها"
            else -> "لا توجد طلبات نشطة"
        }
        binding.tvResultCount.text = "النتائج: ${sourceOrders.size}"
        binding.tvResultCount.visibility = View.VISIBLE
    }

    private fun showDriverSelectionDialog(order: Order) {
        val names = listOf("-- بدون سائق --") + driverList.map { it.name }
        val currentIdx = driverList.indexOfFirst { it.name == order.driverName } + 1

        AlertDialog.Builder(requireContext())
            .setTitle("اختر السائق")
            .setSingleChoiceItems(names.toTypedArray(), currentIdx) { dialog, which ->
                val newDriverName = if (which == 0) "" else driverList[which - 1].name
                val newDriverId = if (which == 0) 0L else driverList[which - 1].id
                viewModel.updateOrder(order.copy(driverName = newDriverName, driverId = newDriverId))
                Toast.makeText(requireContext(),
                    if (newDriverName.isEmpty()) "تم إزالة السائق" else "تم تعيين: $newDriverName",
                    Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        binding.etSearch.text?.clear()
        viewModel.setSearchQuery("")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingDeletes.values.forEach { it.cancel() }
        pendingDeletes.clear()
        _binding = null
    }
}
