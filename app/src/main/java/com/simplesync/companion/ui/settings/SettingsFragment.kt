package com.simplesync.companion.ui.settings

import android.app.Application
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.simplesync.companion.R
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.databinding.FragmentSettingsBinding
import com.simplesync.companion.repository.SyncRepository
import com.simplesync.companion.worker.ScanWorker
import com.simplesync.companion.worker.WhatsAppBackupWorker
import com.simplesync.companion.worker.WhatsAppRestoreWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs.get(app)
    var savedScrollY: Int = 0
    val waEnabled: LiveData<Boolean> = prefs.waEnabled.asLiveData()
    val waHour:    LiveData<Int>     = prefs.waBackupHour.asLiveData()
}

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: SettingsViewModel by viewModels()
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val repo  by lazy { SyncRepository.get(requireContext()) }

    private val waFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            binding.waSwitch.isChecked = false
            return@registerForActivityResult
        }

        val ctx = requireContext()
        val pickedDoc = DocumentFile.fromTreeUri(ctx, uri)

        if (pickedDoc == null) {
            Toast.makeText(ctx, "Could not access the selected folder.", Toast.LENGTH_SHORT).show()
            binding.waSwitch.isChecked = false
            return@registerForActivityResult
        }

        val finalFolder = resolveWhatsAppFolder(pickedDoc)

        if (finalFolder == null) {
            Toast.makeText(
                ctx,
                "Wrong folder selected.\n\nPlease pick:\n  Android › media › com.whatsapp › WhatsApp\n\nOr pick Android › media and the app will create the rest.",
                Toast.LENGTH_LONG
            ).show()
            binding.waSwitch.isChecked = false
            return@registerForActivityResult
        }

        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(ctx, "Could not get folder permission. Please try again.", Toast.LENGTH_SHORT).show()
            binding.waSwitch.isChecked = false
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            prefs.setWaUri(finalFolder.uri.toString())
            prefs.setWaEnabled(true)
            onWaJustEnabled()
        }
    }

    private fun resolveWhatsAppFolder(picked: DocumentFile): DocumentFile? {
        return when (picked.name) {
            "WhatsApp" -> picked

            "com.whatsapp" -> {
                picked.findFile("WhatsApp") ?: picked.createDirectory("WhatsApp")
            }

            "media" -> {
                val comWhatsapp = picked.findFile("com.whatsapp")
                    ?: picked.createDirectory("com.whatsapp")
                    ?: return null
                comWhatsapp.findFile("WhatsApp") ?: comWhatsapp.createDirectory("WhatsApp")
            }

            else -> null
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (vm.savedScrollY > 0) {
            binding.root.post {
                binding.root.scrollTo(0, vm.savedScrollY)
                vm.savedScrollY = 0
            }
        }

        binding.syncNowBtn.setOnClickListener {
            ScanWorker.runNow(requireContext())
            Toast.makeText(requireContext(), "Scan started…", Toast.LENGTH_SHORT).show()
            findNavController().navigate(R.id.queueFragment)
        }

        binding.batteryBtn.setOnClickListener { openBatterySettings() }
        binding.checkIntegrityBtn.setOnClickListener { checkIntegrityStatus() }

        setupWhatsApp()
    }

    // ── WhatsApp ──────────────────────────────────────────────────────────────

    private fun setupWhatsApp() {
        setupHourSpinner()

        vm.waEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.waSwitch.isChecked = enabled
            binding.waOptionsLayout.isVisible = enabled
            updateWaStatusText()
        }

        vm.waHour.observe(viewLifecycleOwner) { hour ->
            val pos = hourToSpinnerPos(hour)
            if (binding.waHourSpinner.selectedItemPosition != pos) {
                binding.waHourSpinner.setSelection(pos)
            }
        }

        binding.waSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == (vm.waEnabled.value == true)) return@setOnCheckedChangeListener
            if (checked) enableWhatsApp() else confirmDisableWhatsApp()
        }

        binding.waHourSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long
                ) {
                    val hour = spinnerPosToHour(pos)
                    lifecycleScope.launch {
                        val current = prefs.waBackupHour.first()
                        if (hour != current) {
                            prefs.setWaBackupHour(hour)
                            if (prefs.waEnabled.first()) {
                                WhatsAppBackupWorker.schedule(requireContext(), hour)
                            }
                        }
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }

        binding.waRestoreBtn.setOnClickListener { confirmRestore() }

        lifecycleScope.launch { refreshWaRestoreButton() }
    }

    private fun setupHourSpinner() {
        val hours = (0..23).filter { it != 2 }.map { h -> String.format("%02d:00", h) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, hours)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.waHourSpinner.adapter = adapter
    }

    private fun hourToSpinnerPos(hour: Int): Int {
        val hours = (0..23).filter { it != 2 }
        return hours.indexOf(hour).coerceAtLeast(0)
    }

    private fun spinnerPosToHour(pos: Int): Int {
        val hours = (0..23).filter { it != 2 }
        return if (pos in hours.indices) hours[pos] else 3
    }

    private fun enableWhatsApp() {
        lifecycleScope.launch {
            val existing = prefs.waUri.first()
            if (existing.isNotEmpty()) {
                prefs.setWaEnabled(true)
                onWaJustEnabled()
            } else {
                val hint = Uri.parse(
                    "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp"
                )
                waFolderPicker.launch(hint)
            }
        }
    }

    private suspend fun onWaJustEnabled() {
        val hour = prefs.waBackupHour.first()
        WhatsAppBackupWorker.schedule(requireContext(), hour)

        val status = repo.getWhatsAppStatus()
        if (status != null && status.hasBackup) {
            showRestorePrompt(status.totalFiles, status.totalBytes)
        } else {
            WhatsAppBackupWorker.runNow(requireContext())
            Toast.makeText(requireContext(), "Starting initial WhatsApp backup…", Toast.LENGTH_SHORT).show()
        }
        updateWaStatusText()
        refreshWaRestoreButton()
    }

    private fun confirmDisableWhatsApp() {
        AlertDialog.Builder(requireContext())
            .setTitle("Disable WhatsApp Backup")
            .setMessage("No more WhatsApp backups will happen. Your existing backup on the server will not be deleted.")
            .setPositiveButton("Disable") { _, _ ->
                lifecycleScope.launch {
                    prefs.setWaEnabled(false)
                    WhatsAppBackupWorker.cancel(requireContext())
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                binding.waSwitch.isChecked = true
            }
            .show()
    }

    private fun showRestorePrompt(totalFiles: Int, totalBytes: Long) {
        val sizeMb = totalBytes / 1_048_576
        AlertDialog.Builder(requireContext())
            .setTitle("Backup Found on Server")
            .setMessage(
                "Found $totalFiles files (${sizeMb} MB) on the server.\n\n" +
                "Do you want to restore your WhatsApp data now?\n\n" +
                "Recommended if this is a new phone or fresh install."
            )
            .setPositiveButton("Restore Now") { _, _ -> confirmRestore() }
            .setNegativeButton("Not Now") { _, _ ->
                WhatsAppBackupWorker.runNow(requireContext())
            }
            .show()
    }

    private fun confirmRestore() {
        AlertDialog.Builder(requireContext())
            .setTitle("Before You Restore")
            .setMessage(
                "Make sure you have NOT opened WhatsApp on this phone yet.\n\n" +
                "Opening WhatsApp before restoring will create a new empty database " +
                "and the restore may not work correctly.\n\n" +
                "Continue with restore?"
            )
            .setPositiveButton("Continue") { _, _ ->
                WhatsAppRestoreWorker.enqueue(requireContext())
                Toast.makeText(requireContext(), "Restore started…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun refreshWaRestoreButton() {
        val enabled = prefs.waEnabled.first()
        if (!enabled) {
            binding.waRestoreBtn.isEnabled = false
            return
        }
        val status = repo.getWhatsAppStatus()
        binding.waRestoreBtn.isEnabled = status?.hasBackup == true
    }

    private fun updateWaStatusText() {
        lifecycleScope.launch {
            val enabled = prefs.waEnabled.first()
            if (!enabled) {
                binding.waStatusText.text = "Disabled"
                return@launch
            }
            val status = repo.getWhatsAppStatus()
            binding.waStatusText.text = if (status != null && status.lastBackup != null) {
                val date = status.lastBackup.split("T").firstOrNull() ?: status.lastBackup
                "Last backup: $date  ·  ${status.totalFiles} files"
            } else {
                "No backup yet"
            }
        }
    }

    // ── battery / integrity ───────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        updateBatteryStatus()
    }

    private fun checkIntegrityStatus() {
        binding.checkIntegrityBtn.isEnabled = false
        binding.checkIntegrityBtn.text = "Checking…"
        binding.integrityStatusText.isVisible = false

        lifecycleScope.launch {
            val flagSet = repo.checkIntegrityFlag()

            if (!flagSet) {
                showIntegrityResult(
                    "✓ No changes detected — all server records are in sync.",
                    isWarning = false
                )
            } else {
                repo.clearAllTrackedFiles()
                repo.acknowledgeIntegrityFlag()
                ScanWorker.runNow(requireContext())
                showIntegrityResult(
                    "⚠ Server integrity changes detected. Local upload history cleared — scanning now. " +
                    "Files still on the server will be skipped; deleted files will be re-uploaded.",
                    isWarning = true
                )
            }

            binding.checkIntegrityBtn.isEnabled = true
            binding.checkIntegrityBtn.text = "Check Integrity Status"
        }
    }

    private fun showIntegrityResult(message: String, isWarning: Boolean) {
        binding.integrityStatusText.text = message
        binding.integrityStatusText.setTextColor(
            if (isWarning)
                com.google.android.material.color.MaterialColors.getColor(
                    binding.integrityStatusText,
                    com.google.android.material.R.attr.colorError
                )
            else
                com.google.android.material.color.MaterialColors.getColor(
                    binding.integrityStatusText,
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                )
        )
        binding.integrityStatusText.isVisible = true
    }

    private fun updateBatteryStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.batteryStatusText.text = "Not required on this Android version"
            binding.batteryBtn.isEnabled = false
            return
        }
        val pm = requireContext().getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(requireContext().packageName)
        binding.batteryStatusText.text = if (exempt)
            "✓ Battery optimisation is disabled – background uploads will work reliably"
        else
            "⚠ Battery optimisation is ON – background uploads may be interrupted"
        binding.batteryBtn.text = if (exempt) "Battery Settings" else "Disable Battery Optimisation"
    }

    private fun openBatterySettings() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            if (!exempt) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                )
            } else {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    })
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
