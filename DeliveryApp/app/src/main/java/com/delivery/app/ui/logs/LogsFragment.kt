package com.delivery.app.ui.logs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.delivery.app.DeliveryApplication
import com.delivery.app.data.model.ErrorLog
import com.delivery.app.databinding.FragmentLogsBinding
import kotlinx.coroutines.launch

class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LogsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as DeliveryApplication
        val dao = app.database.errorLogDao()

        adapter = LogsAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLogs.adapter = adapter

        dao.getAll().observe(viewLifecycleOwner) { logs ->
            binding.tvEmptyLogs.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(logs)
        }

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) {
                dao.getAll().observe(viewLifecycleOwner) { logs ->
                    adapter.submitList(logs)
                }
                return@setOnCheckedStateChangeListener
            }
            val level = when (checkedIds.first()) {
                binding.chipError.id -> "ERROR"
                binding.chipWarn.id -> "WARN"
                binding.chipInfo.id -> "INFO"
                binding.chipDebug.id -> "DEBUG"
                else -> null
            }
            if (level != null) {
                dao.getByLevel(level).observe(viewLifecycleOwner) { logs ->
                    adapter.submitList(logs)
                }
            } else {
                dao.getAll().observe(viewLifecycleOwner) { logs ->
                    adapter.submitList(logs)
                }
            }
        }

        binding.btnClearLogs.setOnClickListener {
            lifecycleScope.launch {
                dao.deleteAll()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
