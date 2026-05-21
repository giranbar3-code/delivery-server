package com.delivery.app.ui.settings

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.delivery.app.DeliveryApplication
import com.delivery.app.data.OfficeManager
import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.data.remote.RetrofitClient
import com.delivery.app.data.repository.SyncManager
import com.delivery.app.databinding.FragmentSettingsBinding
import com.delivery.app.ui.OrderViewModel
import com.delivery.app.ui.OrderViewModelFactory
import com.delivery.app.utils.BackupUtils
import com.delivery.app.utils.ExportUtils
import com.delivery.app.utils.NotificationHelper
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val orderViewModel: OrderViewModel by activityViewModels {
        OrderViewModelFactory(requireActivity().application)
    }

    private var allOrders = listOf<com.delivery.app.data.model.Order>()
    private val startOfToday: Long by lazy {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(requireContext(), "✓ تم تفعيل الإشعارات", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val tempFile = File(requireContext().cacheDir, "backup_temp.db")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                BackupUtils.importBackup(requireContext(), tempFile) { success ->
                    if (success) {
                        Toast.makeText(
                            requireContext(),
                            "تم استعادة النسخة الاحتياطية — أعد تشغيل التطبيق",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "فشل في استعادة النسخة الاحتياطية",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private val saveBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val ok = BackupUtils.writeBackupToUri(requireContext(), uri)
            Toast.makeText(
                requireContext(),
                if (ok) "✓ تم حفظ النسخة الاحتياطية" else "✗ فشل حفظ النسخة الاحتياطية",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requestNotificationPermission()
        loadServerSettings()
        setupServerSettings()
        setupSyncButton()
        setupBackupButtons()
        setupExportButtons()
        setupDarkMode()
        setupCustomersButton()
        setupPinLock()
        setupOfficeId()
        setupOfficeLink()
        observeOrders()
        observeCurrentOffice()
    }

    private fun loadServerSettings() {
        val context = requireContext()
        binding.etServerUrl.setText(RetrofitClient.getBaseUrl(context))
        binding.etApiKey.setText(RetrofitClient.getApiKey(context))
    }

    private fun setupServerSettings() {
        binding.btnSaveServerSettings.setOnClickListener {
            val serverUrl = binding.etServerUrl.text.toString().trim()
            val apiKey = binding.etApiKey.text.toString().trim()

            if (serverUrl.isEmpty()) {
                Toast.makeText(requireContext(), "رابط الخادم مطلوب", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!serverUrl.startsWith("https://")) {
                Toast.makeText(requireContext(), "الرابط يجب أن يبدأ بـ https:// للأمان", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (apiKey.isEmpty()) {
                Toast.makeText(requireContext(), "مفتاح API مطلوب", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            RetrofitClient.setBaseUrl(requireContext(), serverUrl)
            RetrofitClient.setApiKey(requireContext(), apiKey)

            updateOfficeLink()
            Toast.makeText(requireContext(), "تم حفظ إعدادات الخادم ✅", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupSyncButton() {
        binding.btnSyncOrders.setOnClickListener {
            val app = requireActivity().application as DeliveryApplication
            lifecycleScope.launch {
                binding.btnSyncOrders.isEnabled = false
                binding.btnSyncOrders.text = "جارٍ المزامنة..."
                binding.progressSync.visibility = View.VISIBLE
                when (val result = app.syncManager.syncNewOrders()) {
                    is SyncManager.SyncResult.Success -> {
                        if (result.count > 0) {
                            Toast.makeText(requireContext(), "✅ تم استلام ${result.count} طلب جديد", Toast.LENGTH_LONG).show()
                            NotificationHelper.notifyNewOrders(requireContext(), result.count)
                        } else {
                            Toast.makeText(requireContext(), "لا توجد طلبات جديدة", Toast.LENGTH_SHORT).show()
                        }
                    }
                    is SyncManager.SyncResult.Error -> {
                        Toast.makeText(requireContext(), "❌ ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
                binding.btnSyncOrders.isEnabled = true
                binding.btnSyncOrders.text = "🔄 مزامنة الطلبات"
                binding.progressSync.visibility = View.GONE
            }
        }
    }

    private fun setupBackupButtons() {
        binding.btnExportBackup.setOnClickListener {
            saveBackup.launch(BackupUtils.getBackupFileName())
        }

        binding.btnImportBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
            }
            pickFile.launch(intent)
        }
    }

    private fun setupExportButtons() {
        binding.btnExportPdf.setOnClickListener {
            val orders = allOrders.filter {
                it.statusEnum == DeliveryStatus.DELIVERED && it.createdAt >= startOfToday
            }
            
            if (orders.isEmpty()) {
                Toast.makeText(requireContext(), "لا توجد طلبات موصّلة اليوم للتصدير", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ExportUtils.exportToPdf(requireContext(), orders, "تقرير اليوم")
        }

        binding.btnExportCsv.setOnClickListener {
            val orders = allOrders.filter {
                it.statusEnum == DeliveryStatus.DELIVERED && it.createdAt >= startOfToday
            }

            if (orders.isEmpty()) {
                Toast.makeText(requireContext(), "لا توجد طلبات موصّلة اليوم للتصدير", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ExportUtils.exportToCsv(requireContext(), orders, "تقرير اليوم")
        }
    }

    private fun observeOrders() {
        orderViewModel.allOrders.observe(viewLifecycleOwner) { orders ->
            allOrders = orders
        }
    }

    private fun setupDarkMode() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        binding.switchDarkMode.isChecked = prefs.getBoolean("dark_mode", false)

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dark_mode", isChecked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun setupOfficeId() {
        val ctx = requireContext()
        val currentId = OfficeManager.currentOfficeId.value ?: 0L
        binding.etOfficeId.setText(currentId.toString())
        binding.btnSaveOfficeId.setOnClickListener {
            val id = binding.etOfficeId.text.toString().trim().toLongOrNull()
            if (id == null || id < 0) {
                Toast.makeText(ctx, "رقم المكتب يجب أن يكون 0 أو أكثر", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            OfficeManager.switchOffice(ctx, id)
            Toast.makeText(ctx, "تم تعيين رقم المكتب: $id", Toast.LENGTH_SHORT).show()
            observeCurrentOffice()
        }
    }

    private fun observeCurrentOffice() {
        val currentId = OfficeManager.currentOfficeId.value ?: 0L
        val text = if (currentId == 0L) "المكتب الرئيسي" else "المكتب رقم $currentId"
        binding.tvCurrentOfficeName.text = text
        updateOfficeLink()
    }

    private fun setupOfficeLink() {
        updateOfficeLink()
        binding.btnCopyOfficeLink.setOnClickListener {
            val link = binding.tvOfficeLink.text.toString()
            if (link.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("رابط المكتب", link))
                Toast.makeText(requireContext(), "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateOfficeLink() {
        val baseUrl = RetrofitClient.getBaseUrl(requireContext()).trimEnd('/')
        val officeId = OfficeManager.currentOfficeId.value ?: 0L
        val link = if (officeId == 0L) "$baseUrl/" else "$baseUrl/office/$officeId"
        binding.tvOfficeLink.text = link
    }

    private fun setupCustomersButton() {
        binding.btnCustomers.setOnClickListener {
            findNavController().navigate(com.delivery.app.R.id.action_settings_to_customers)
        }
    }

    private fun getPinPrefs(): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            "delivery_pin_prefs",
            masterKey,
            requireContext(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun setupPinLock() {
        val securePrefs = getPinPrefs()
        val hasPin = securePrefs.contains("pin")
        val pinEnabled = securePrefs.getBoolean("pin_enabled", false)

        if (hasPin) {
            binding.switchPinEnabled.isChecked = pinEnabled
            binding.layoutPin.visibility = View.GONE
            binding.btnSavePin.visibility = View.GONE
        }

        binding.switchPinEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasPin) {
                binding.layoutPin.visibility = View.VISIBLE
                binding.btnSavePin.visibility = View.VISIBLE
                binding.btnSavePin.text = "تفعيل القفل"
            } else if (isChecked && hasPin) {
                securePrefs.edit().putBoolean("pin_enabled", true).apply()
                Toast.makeText(requireContext(), "تم تفعيل القفل", Toast.LENGTH_SHORT).show()
            } else {
                securePrefs.edit().putBoolean("pin_enabled", false).apply()
                binding.layoutPin.visibility = View.GONE
                binding.btnSavePin.visibility = View.GONE
                Toast.makeText(requireContext(), "تم إلغاء القفل", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSavePin.setOnClickListener {
            val pin = binding.etPin.text.toString().trim()
            if (pin.length != 4 || pin.toIntOrNull() == null) {
                Toast.makeText(requireContext(), "الرجاء إدخال 4 أرقام", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            securePrefs.edit().putString("pin", pin).apply()
            securePrefs.edit().putBoolean("pin_enabled", true).apply()
            binding.layoutPin.visibility = View.GONE
            binding.btnSavePin.visibility = View.GONE
            binding.etPin.text?.clear()
            Toast.makeText(requireContext(), "تم حفظ الرقم السري", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
