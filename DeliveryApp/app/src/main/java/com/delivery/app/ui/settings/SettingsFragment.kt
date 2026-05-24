package com.delivery.app.ui.settings

import android.Manifest
import android.app.Activity
import androidx.appcompat.app.AlertDialog
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
import com.delivery.app.data.SetupConfig
import com.delivery.app.data.model.DeliveryStatus
import com.delivery.app.data.model.Driver
import com.delivery.app.data.remote.CreateDriverRequest
import com.delivery.app.data.remote.RetrofitClient
import com.delivery.app.data.repository.SyncManager
import com.delivery.app.databinding.FragmentSettingsBinding
import com.delivery.app.ui.OrderViewModel
import com.delivery.app.ui.OrderViewModelFactory
import com.delivery.app.utils.BackupUtils
import com.delivery.app.utils.ExportUtils
import com.delivery.app.utils.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val orderViewModel: OrderViewModel by activityViewModels {
        OrderViewModelFactory(requireActivity().application)
    }

    private var allOrders = listOf<com.delivery.app.data.model.Order>()
    /** تخزين مؤقت لكلمة مرور النسخة الاحتياطية لحين اختيار الملف */
    private var pendingBackupPassword: String? = null
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
                // التحقق من توقيع الملف لمعرفة نوع التشفير
                val header = if (tempFile.exists() && tempFile.length() >= 4) {
                    tempFile.readBytes().copyOfRange(0, 4)
                } else null
                // "DLVR" في بداية الملف = مشفر بكلمة مرور
                if (header != null && header.contentEquals(byteArrayOf(0x44, 0x4C, 0x56, 0x52))) {
                    showPasswordDialogForImport(tempFile)
                } else {
                    // ملف قديم (KeyStore)
                    BackupUtils.importBackup(requireContext(), tempFile) { success ->
                        showImportResult(success)
                    }
                }
            }
        }
    }

    private fun showPasswordDialogForImport(tempFile: File) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "كلمة مرور النسخة الاحتياطية"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(ctx)
            .setTitle("🔐 فك تشفير النسخة الاحتياطية")
            .setMessage("هذه النسخة محمية بكلمة مرور.\nأدخل كلمة المرور التي استخدمتها عند التصدير.")
            .setView(input)
            .setPositiveButton("استعادة") { _, _ ->
                val password = input.text.toString()
                if (password.isEmpty()) {
                    Toast.makeText(ctx, "الرجاء إدخال كلمة المرور", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BackupUtils.importBackup(ctx, tempFile, password) { success ->
                    showImportResult(success)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showImportResult(success: Boolean) {
        Toast.makeText(
            requireContext(),
            if (success) "تم استعادة النسخة الاحتياطية — أعد تشغيل التطبيق"
            else "فشل في استعادة النسخة الاحتياطية (كلمة مرور خاطئة أو ملف غير صالح)",
            Toast.LENGTH_LONG
        ).show()
    }

    private val saveBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val password = pendingBackupPassword
            pendingBackupPassword = null
            val ok = BackupUtils.writeBackupToUri(requireContext(), uri, password)
            Toast.makeText(
                requireContext(),
                if (ok) "✓ تم حفظ النسخة الاحتياطية" else "✗ فشل حفظ النسخة الاحتياطية",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupBackupButtons() {
        binding.btnExportBackup.setOnClickListener {
            showPasswordDialogForExport()
        }

        binding.btnImportBackup.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
            }
            pickFile.launch(intent)
        }
    }

    private fun showPasswordDialogForExport() {
        val ctx = requireContext()
        val etPassword = android.widget.EditText(ctx).apply {
            hint = "كلمة المرور"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etConfirm = android.widget.EditText(ctx).apply {
            hint = "تأكيد كلمة المرور"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
            addView(etPassword)
            addView(android.widget.TextView(ctx).apply { text = ""; setPadding(0, 8, 0, 0) })
            addView(etConfirm)
        }
        AlertDialog.Builder(ctx)
            .setTitle("🔐 كلمة مرور النسخة الاحتياطية")
            .setMessage("أدخل كلمة مرور لحماية الملف.\nستحتاجها عند الاستيراد على أي جهاز.")
            .setView(layout)
            .setPositiveButton("تصدير") { _, _ ->
                val pw = etPassword.text.toString()
                val confirm = etConfirm.text.toString()
                if (pw.length < 4) {
                    Toast.makeText(ctx, "كلمة المرور يجب أن تكون 4 أحرف على الأقل", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (pw != confirm) {
                    Toast.makeText(ctx, "كلمة المرور غير متطابقة", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingBackupPassword = pw
                saveBackup.launch(BackupUtils.getBackupFileName())
            }
            .setNegativeButton("بدون كلمة مرور") { _, _ ->
                pendingBackupPassword = null
                saveBackup.launch(BackupUtils.getBackupFileName())
            }
            .show()
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
        setupCertPins()
        setupSyncButton()
        setupBackupButtons()
        setupExportButtons()
        setupDarkMode()
        setupCustomersButton()
        setupLogsButton()
        setupDriverSync()
        setupPinLock()
        setupProtectedOfficeSection()
        observeOrders()
        observeCurrentOffice()
    }

    private fun loadServerSettings() {
        val context = requireContext()
        binding.etServerUrl.setText(RetrofitClient.getBaseUrl(context))
        binding.etApiKey.setText(RetrofitClient.getApiKey(context))
        binding.etCertPins.setText(RetrofitClient.getCertPins(context))
    }

    private fun setupCertPins() {
        binding.btnSaveCertPins.setOnClickListener {
            val pins = binding.etCertPins.text.toString().trim()
            RetrofitClient.setCertPins(requireContext(), pins)
            Toast.makeText(requireContext(), "تم حفظ إعدادات ربط الشهادات", Toast.LENGTH_SHORT).show()
        }
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

    private fun setupExportButtons() {
        binding.btnExportPdf.setOnClickListener {
            val orders = allOrders.filter {
                it.statusEnum == DeliveryStatus.DELIVERED && it.createdAt >= startOfToday
            }

            if (orders.isEmpty()) {
                Toast.makeText(requireContext(), "لا توجد طلبات موصّلة اليوم للتصدير", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showExportWarningDialog("PDF") {
                ExportUtils.exportToPdf(requireContext(), orders, "تقرير اليوم")
            }
        }

        binding.btnExportCsv.setOnClickListener {
            val orders = allOrders.filter {
                it.statusEnum == DeliveryStatus.DELIVERED && it.createdAt >= startOfToday
            }

            if (orders.isEmpty()) {
                Toast.makeText(requireContext(), "لا توجد طلبات موصّلة اليوم للتصدير", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showExportWarningDialog("CSV") {
                ExportUtils.exportToCsv(requireContext(), orders, "تقرير اليوم")
            }
        }
    }

    private fun showExportWarningDialog(format: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ تنبيه الخصوصية")
            .setMessage(
                "ملف $format سيحتوي على بيانات حساسة:\n" +
                "• أسماء وأرقام الزبائن\n" +
                "• عناوين التوصيل\n" +
                "• أسعار الطلبيات\n\n" +
                "يرجى التأكد من:\n" +
                "• عدم مشاركة الملف مع أشخاص غير مصرح لهم\n" +
                "• حذف الملف بعد الاستخدام\n\n" +
                "هل تريد المتابعة؟"
            )
            .setPositiveButton("تصدير") { _, _ -> onConfirm() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun observeOrders() {
        orderViewModel.allOrders.observe(viewLifecycleOwner) { orders ->
            allOrders = orders
        }
    }

    private fun setupDriverSync() {
        binding.btnSyncDrivers.setOnClickListener {
            val app = requireActivity().application as DeliveryApplication
            lifecycleScope.launch {
                binding.btnSyncDrivers.isEnabled = false
                binding.btnSyncDrivers.text = "جارٍ..."
                binding.progressDriverSync.visibility = View.VISIBLE

                try {
                    val officeId = OfficeManager.currentOfficeId.value ?: 0L
                    val localDrivers = withContext(Dispatchers.IO) {
                        app.driverRepository.getDriversSync(officeId)
                    }

                    if (localDrivers.isEmpty()) {
                        Toast.makeText(requireContext(), "لا يوجد سائقون محليون للمزامنة", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    showDriverSyncDialog(localDrivers, app, officeId)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "❌ فشل: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnSyncDrivers.isEnabled = true
                    binding.btnSyncDrivers.text = "مزامنة"
                    binding.progressDriverSync.visibility = View.GONE
                }
            }
        }
    }

    private fun showDriverSyncDialog(
        localDrivers: List<Driver>,
        @Suppress("UNUSED_PARAMETER") app: DeliveryApplication,
        officeId: Long
    ) {
        val ctx = requireContext()
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }

        layout.addView(android.widget.TextView(ctx).apply {
            text = "السائقون المحليون (${localDrivers.size})"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        val phoneInputs = mutableListOf<android.widget.EditText>()
        val pinInputs = mutableListOf<android.widget.EditText>()

        localDrivers.forEachIndexed { index, driver ->
            val driverLayout = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }

            driverLayout.addView(android.widget.TextView(ctx).apply {
                text = "${index + 1}. ${driver.name}"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            val etPhone = android.widget.EditText(ctx).apply {
                hint = "رقم الهاتف (مثال: +9639XXXXXXXX)"
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setText(driver.phone.ifEmpty { "+963" })
                maxLines = 1
            }
            phoneInputs.add(etPhone)
            driverLayout.addView(etPhone)

            val etPin = android.widget.EditText(ctx).apply {
                hint = "الرمز السري"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("1234")
                maxLines = 1
            }
            pinInputs.add(etPin)
            driverLayout.addView(etPin)

            if (index < localDrivers.size - 1) {
                val sep = android.view.View(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(0xFFE0E0E0.toInt())
                }
                sep.setPadding(0, 8, 0, 0)
                driverLayout.addView(sep)
            }
            layout.addView(driverLayout)
        }

        val scrollView = android.widget.ScrollView(ctx).apply { addView(layout) }

        AlertDialog.Builder(ctx)
            .setTitle("🔐 مزامنة السائقين")
            .setMessage("أدخل رقم الهاتف والرمز السري لكل سائق")
            .setView(scrollView)
            .setPositiveButton("مزامنة") { _, _ ->
                lifecycleScope.launch {
                    syncDriversToServer2(localDrivers, phoneInputs, pinInputs, officeId)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private suspend fun syncDriversToServer2(
        localDrivers: List<Driver>,
        phoneInputs: List<android.widget.EditText>,
        pinInputs: List<android.widget.EditText>,
        officeId: Long
    ) {
        try {
            val api = RetrofitClient.getApi(requireContext())

            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "بدء مزامنة ${localDrivers.size} سائق (المكتب $officeId)", Toast.LENGTH_SHORT).show()
            }

            var synced = 0
            var errors = 0
            var skipped = 0
            var firstError: String? = null

            for (i in localDrivers.indices) {
                val driver = localDrivers[i]
                val phone = phoneInputs[i].text.toString().trim()
                val pin = pinInputs[i].text.toString().trim()

                if (phone.isEmpty() || phone == "+963") {
                    skipped++
                    continue
                }
                if (pin.length < 3) {
                    skipped++
                    continue
                }

                try {
                    val result = withContext(Dispatchers.IO) {
                        api.createRemoteDriver(CreateDriverRequest(
                            name = driver.name,
                            phone = phone,
                            pin = pin,
                            officeId = officeId
                        ))
                    }
                    if (result.error != null) {
                        if (firstError == null) firstError = result.error
                        errors++
                    } else {
                        synced++
                    }
                } catch (e: Exception) {
                    if (firstError == null) firstError = e.localizedMessage
                    errors++
                }
            }

            val msg = buildString {
                append("✅ تمت مزامنة $synced سائق")
                if (errors > 0) append("، فشل $errors")
                if (skipped > 0) append("، تخطي $skipped")
                if (firstError != null) append("\nأول خطأ: $firstError")
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "❌ فشل الاتصال بالخادم: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
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

    private fun setupProtectedOfficeSection() {
        // إخفاء حقول تحرير المكتب ورابط الاستقبال
        binding.layoutOfficeId.visibility = View.GONE
        binding.btnSaveOfficeId.visibility = View.GONE
        binding.btnCopyOfficeLink.visibility = View.GONE
        binding.tvOfficeLink.visibility = View.GONE
        // إخفاء الفاصل والنص العلوي للرابط
        try {
            binding.root.findViewById<View>(com.delivery.app.R.id.office_link_divider)?.visibility = View.GONE
            binding.root.findViewById<View>(com.delivery.app.R.id.tv_office_link_label)?.visibility = View.GONE
        } catch (_: Exception) {}

        // جعل بطاقة المكتب قابلة للنقر — تطلب الرقم السري
        try {
            val officeCard = binding.root.findViewById<com.google.android.material.card.MaterialCardView>(
                com.delivery.app.R.id.office_card
            )
            officeCard?.isClickable = true
            officeCard?.isFocusable = true
            officeCard?.setOnClickListener {
                verifyPinBeforeOfficeAccess()
            }
        } catch (_: Exception) {}
    }

    private fun verifyPinBeforeOfficeAccess() {
        val securePrefs = getPinPrefs()
        if (!securePrefs.getBoolean("pin_enabled", false)) {
            showOfficeConfigDialog()
            return
        }
        val savedPin = securePrefs.getString("pin", "") ?: ""
        if (savedPin.isEmpty()) {
            showOfficeConfigDialog()
            return
        }

        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            hint = "أدخل الرقم السري"
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("🔒 حماية المكتب")
            .setMessage("أدخل الرقم السري لعرض إعدادات المكتب")
            .setView(input)
            .setPositiveButton("تأكيد", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = input.text.toString().trim()
                if (entered == savedPin) {
                    dialog.dismiss()
                    showOfficeConfigDialog()
                } else {
                    Toast.makeText(requireContext(), "رقم سري خاطئ", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun showOfficeConfigDialog() {
        val ctx = requireContext()
        val currentId = OfficeManager.currentOfficeId.value ?: 0L
        val baseUrl = RetrofitClient.getBaseUrl(ctx).trimEnd('/')
        val link = if (currentId == 0L) "$baseUrl/" else "$baseUrl/office/$currentId"

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val tvId = android.widget.TextView(ctx).apply {
            text = "رقم المكتب: $currentId"
            textSize = 16f
        }
        val tvLinkLabel = android.widget.TextView(ctx).apply {
            text = "رابط الاستقبال:"
            textSize = 13f
            setPadding(0, 16, 0, 4)
        }
        val tvLink = android.widget.TextView(ctx).apply {
            text = link
            textSize = 14f
            setTextColor(0xFF1565C0.toInt())
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        layout.addView(tvId)
        layout.addView(tvLinkLabel)
        layout.addView(tvLink)

        val inputLayout = com.google.android.material.textfield.TextInputLayout(ctx).apply {
            hint = "رقم مكتب جديد (اترك 0 للمكتب الرئيسي)"
            setPadding(0, 16, 0, 0)
            setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE)
        }
        val etNewId = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentId.toString())
        }
        inputLayout.addView(etNewId)
        layout.addView(inputLayout)

        AlertDialog.Builder(ctx)
            .setTitle("⚙️ إعدادات المكتب")
            .setView(layout)
            .setPositiveButton("حفظ", null)
            .setNeutralButton("نسخ الرابط") { _, _ ->
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("رابط المكتب", link))
                Toast.makeText(ctx, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
            .also { dialog ->
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val newId = etNewId.text.toString().trim().toLongOrNull() ?: currentId
                    if (newId == currentId) {
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    showActivationKeyPrompt(ctx, newId, dialog)
                }
            }
    }

    private fun showActivationKeyPrompt(ctx: Context, newOfficeId: Long, parentDialog: androidx.appcompat.app.AlertDialog) {
        val input = android.widget.EditText(ctx).apply {
            hint = "رمز التفعيل"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(ctx)
            .setTitle("🔐 تأكيد التغيير")
            .setMessage("أدخل رمز التفعيل لتأكيد تغيير رقم المكتب")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("تأكيد") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(ctx, "الرجاء إدخال رمز التفعيل", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val serverResult = SetupConfig.verifyWithServer(ctx, key)
                    val isValid = when {
                        serverResult == true -> true
                        serverResult == null -> SetupConfig.verifyLocally(key)
                        else -> false
                    }
                    if (isValid) {
                        OfficeManager.switchOffice(ctx, newOfficeId)
                        observeCurrentOffice()
                        parentDialog.dismiss()
                        Toast.makeText(ctx, "تم تغيير رقم المكتب إلى: $newOfficeId", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "رمز التفعيل خطأ — لم يتم التغيير", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun observeCurrentOffice() {
        val currentId = OfficeManager.currentOfficeId.value ?: 0L
        val text = if (currentId == 0L) "المكتب الرئيسي" else "المكتب رقم $currentId"
        binding.tvCurrentOfficeName.text = text
    }

    private fun setupCustomersButton() {
        binding.btnCustomers.setOnClickListener {
            findNavController().navigate(com.delivery.app.R.id.action_settings_to_customers)
        }
    }

    private fun setupLogsButton() {
        binding.btnLogs.setOnClickListener {
            findNavController().navigate(com.delivery.app.R.id.action_settings_to_logs)
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
