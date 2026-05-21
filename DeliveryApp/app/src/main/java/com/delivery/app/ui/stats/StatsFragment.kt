package com.delivery.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.delivery.app.R
import com.delivery.app.data.local.DailyStat
import com.delivery.app.data.local.StatusStat
import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.databinding.FragmentStatsBinding
import com.delivery.app.ui.OrderViewModel
import com.delivery.app.ui.OrderViewModelFactory
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OrderViewModel by activityViewModels {
        OrderViewModelFactory(requireActivity().application)
    }

    private var chartMode = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupPeriodTabs()
        setupChartModeTabs()
        observeStats()
    }

    private fun setupPeriodTabs() {
        binding.tabPeriod.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    viewModel.setStatsPeriod(tab?.position ?: 0)
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
    }

    private fun setupChartModeTabs() {
        binding.tabChartMode.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    chartMode = tab?.position ?: 0
                    viewModel.dailyStats.value?.let { updateBarChart(it) }
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
    }

    private fun observeStats() {
        viewModel.deliveredCount.observe(viewLifecycleOwner) { count ->
            binding.tvDeliveredCount.text = count.toString()
        }

        viewModel.totalCount.observe(viewLifecycleOwner) { total ->
            binding.tvTotalCount.text = total.toString()
            val delivered = viewModel.deliveredCount.value ?: 0
            val pct = if (total > 0) (delivered * 100 / total) else 0
            binding.tvDeliveryRate.text = "نسبة التوصيل: $pct%"
            binding.progressDelivery.progress = pct
        }

        viewModel.totalRevenue.observe(viewLifecycleOwner) { revenue ->
            binding.tvTotalRevenue.text = String.format("%,.0f ل.س", revenue ?: 0.0)
        }

        viewModel.totalPurchaseRevenue.observe(viewLifecycleOwner) { revenue ->
            binding.tvTotalPurchase.text = String.format("%,.0f ل.س", revenue ?: 0.0)
        }

        viewModel.driverStats.observe(viewLifecycleOwner) { stats ->
            if (stats.isEmpty()) {
                binding.tvDriverStats.text = "لا توجد إحصائيات"
            } else {
                val sb = StringBuilder()
                stats.forEachIndexed { i, s ->
                    val medal = when (i) {
                        0 -> "\uD83E\uDD47"; 1 -> "\uD83E\uDD48"; 2 -> "\uD83E\uDD49"; else -> "  "
                    }
                    sb.appendLine("$medal ${s.driverName}  ——  ${s.orderCount} طلبية")
                }
                binding.tvDriverStats.text = sb.toString().trimEnd()
            }
        }

        viewModel.statsPeriod.observe(viewLifecycleOwner) { period ->
            binding.tvPeriodLabel.text = when (period) {
                0 -> "إحصائيات اليوم"
                1 -> "إحصائيات الأسبوع"
                else -> "إحصائيات الشهر"
            }
        }

        viewModel.dailyStats.observe(viewLifecycleOwner) { stats ->
            updateBarChart(stats)
        }

        viewModel.statusDistribution.observe(viewLifecycleOwner) { stats ->
            updatePieChart(stats)
        }
    }

    private fun updateBarChart(stats: List<DailyStat>) {
        val entries = stats.mapIndexed { i, s ->
            BarEntry(i.toFloat(), if (chartMode == 1) s.dayRevenue.toFloat() else s.dayOrders.toFloat())
        }
        if (entries.isEmpty()) {
            binding.chartDaily.clear()
            binding.chartDaily.invalidate()
            return
        }
        val dataSet = BarDataSet(entries, if (chartMode == 1) "الإيرادات" else "الطلبات").apply {
            color = if (chartMode == 1) resources.getColor(R.color.revenue_purple) else resources.getColor(R.color.primary_blue)
            valueTextSize = 10f
            valueTextColor = resources.getColor(R.color.text_secondary)
        }
        binding.chartDaily.data = BarData(dataSet)
        binding.chartDaily.description.isEnabled = false
        binding.chartDaily.legend.isEnabled = false
        binding.chartDaily.setFitBars(true)
        binding.chartDaily.axisLeft.isEnabled = true
        binding.chartDaily.axisRight.isEnabled = false
        binding.chartDaily.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            setDrawGridLines(false)
            val sdf = SimpleDateFormat("dd/MM", Locale.getDefault())
            valueFormatter = IndexAxisValueFormatter(
                stats.map { sdf.format(Date(it.dayBucket * 86400000)) }
            )
            textSize = 9f
        }
        binding.chartDaily.axisLeft.apply {
            setDrawGridLines(true)
            textSize = 9f
        }
        binding.chartDaily.animateY(500)
        binding.chartDaily.invalidate()
    }

    private fun updatePieChart(stats: List<StatusStat>) {
        if (stats.isEmpty()) {
            binding.chartStatus.clear()
            binding.chartStatus.invalidate()
            return
        }
        val statusColors = mapOf(
            "PENDING" to resources.getColor(R.color.status_pending),
            "PREPARING" to resources.getColor(R.color.status_preparing),
            "OUT_FOR_DELIVERY" to resources.getColor(R.color.status_out_for_delivery),
            "DELIVERED" to resources.getColor(R.color.status_delivered),
            "RETURNED" to resources.getColor(R.color.status_returned),
            "CANCELLED" to resources.getColor(R.color.status_cancelled)
        )
        val statusLabels = stats.associate { it.deliveryStatus to (try { DeliveryStatus.valueOf(it.deliveryStatus).label } catch (_: Exception) { it.deliveryStatus }) }
        val entries = stats.map { stat ->
            PieEntry(stat.statusCount.toFloat(), statusLabels[stat.deliveryStatus] ?: stat.deliveryStatus)
        }
        val dataSet = PieDataSet(entries, "").apply {
            colors = stats.map { statusColors[it.deliveryStatus] ?: resources.getColor(R.color.text_hint) }
            valueTextSize = 12f
            valueTextColor = resources.getColor(R.color.white)
            sliceSpace = 2f
            selectionShift = 0f
        }
        binding.chartStatus.data = PieData(dataSet)
        binding.chartStatus.description.isEnabled = false
        binding.chartStatus.setUsePercentValues(true)
        binding.chartStatus.isDrawHoleEnabled = true
        binding.chartStatus.holeRadius = 40f
        binding.chartStatus.transparentCircleRadius = 45f
        binding.chartStatus.legend.apply {
            isEnabled = true
            textSize = 11f
            verticalAlignment = com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
            horizontalAlignment = com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.CENTER
            orientation = com.github.mikephil.charting.components.Legend.LegendOrientation.HORIZONTAL
            setDrawInside(false)
        }
        binding.chartStatus.animateY(500)
        binding.chartStatus.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
