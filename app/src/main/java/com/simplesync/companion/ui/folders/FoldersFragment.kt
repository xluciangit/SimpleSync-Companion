package com.simplesync.companion.ui.folders

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplesync.companion.R
import com.simplesync.companion.data.db.FolderConfig
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.databinding.FragmentFoldersBinding
import com.simplesync.companion.databinding.ItemFolderConnectBinding
import com.simplesync.companion.databinding.ItemFolderGridBinding
import com.simplesync.companion.repository.SyncRepository
import com.simplesync.companion.data.db.JobStatus
import com.simplesync.companion.worker.WhatsAppBackupWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class FoldersFragment : Fragment() {

    private var _binding: FragmentFoldersBinding? = null
    private val binding get() = _binding!!
    private val vm: FoldersViewModel by viewModels()
    private val prefs by lazy { Prefs.get(requireContext()) }
    private lateinit var folderAdapter: FolderGridAdapter
    private lateinit var connectAdapter: ConnectFolderAdapter

    // pending "add new folder" state
    private var pendingDisplay  = ""
    private var pendingRemote   = ""
    private var pendingInterval = 60
    private var pendingHidden   = false

    // pending "connect existing server folder" state
    private var pendingConnectRemoteName = ""

    private val intervalOptions: List<Pair<String, Int>> = buildList {
        listOf(15, 30, 45, 60).forEach { add("$it minutes" to it) }
        (2..24).forEach { add("$it hours" to it * 60) }
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        vm.addFolder(pendingDisplay, uri, pendingRemote, pendingInterval, pendingHidden)
    }

    // picker for reconnecting an existing server folder
    private val connectFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null || pendingConnectRemoteName.isEmpty()) return@registerForActivityResult
        onConnectFolderPicked(uri, pendingConnectRemoteName)
        pendingConnectRemoteName = ""
    }

    // picker for reconnecting the WA folder from a "connect" card
    private val connectWaFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        onConnectWaFolderPicked(uri)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentFoldersBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        folderAdapter = FolderGridAdapter(
            onSettings = { cfg -> showFolderSettingsSheet(cfg) },
            onToggle   = { vm.toggleActive(it) }
        )
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = folderAdapter
        }

        connectAdapter = ConnectFolderAdapter { remoteName ->
            showConnectFolderDialog(remoteName)
        }
        binding.connectRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = connectAdapter
        }

        var prevCount = -1
        vm.folders.observe(viewLifecycleOwner) { list ->
            if (prevCount >= 0 && list.size > prevCount) {
                Toast.makeText(requireContext(), "Folder added – first scan starting", Toast.LENGTH_SHORT).show()
            }
            prevCount = list.size
            folderAdapter.submitList(list)
            updateEmptyState(list)
        }

        vm.unconnectedServerFolders.observe(viewLifecycleOwner) { list ->
            connectAdapter.submitList(list)
            binding.connectSection.isVisible = list.isNotEmpty()
            updateEmptyState(folderAdapter.currentList)
        }

        vm.waBackupOnServer.observe(viewLifecycleOwner) { hasWaOnServer ->
            prefs.waEnabled.asLiveData().observe(viewLifecycleOwner) { waEnabled ->
                binding.waConnectCard.isVisible = hasWaOnServer && !waEnabled
            }
        }

        vm.error.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }

        binding.waConnectBtn.setOnClickListener { showConnectWaDialog() }

        prefs.waEnabled.asLiveData().observe(viewLifecycleOwner) { enabled ->
            binding.waFolderCard.isVisible = enabled
            if (!enabled) updateEmptyState(folderAdapter.currentList)
            if (enabled) vm.refreshWaPhoneSize()
        }

        vm.waLastBackup.observe(viewLifecycleOwner) { lastBackup ->
            binding.waFolderStatus.text = when {
                lastBackup.isNullOrEmpty() -> ""
                else -> {
                    val date = lastBackup.split("T").firstOrNull() ?: lastBackup
                    "Last backup: $date"
                }
            }
        }

        vm.waServerBytes.observe(viewLifecycleOwner) { bytes ->
            if (bytes >= 0) {
                binding.waSizeRow.isVisible = true
                val files = vm.waServerFiles.value ?: -1
                val count = if (files >= 0) "  $files files" else ""
                binding.waServerSize.text = "Server: ${formatBytes(bytes)}$count"
            }
        }

        vm.waServerFiles.observe(viewLifecycleOwner) { files ->
            val bytes = vm.waServerBytes.value ?: -1L
            if (bytes >= 0 && files >= 0) {
                binding.waSizeRow.isVisible = true
                binding.waServerSize.text = "Server: ${formatBytes(bytes)}  $files files"
            }
        }

        vm.waPhoneBytes.observe(viewLifecycleOwner) { bytes ->
            if (bytes >= 0) {
                binding.waSizeRow.isVisible = true
                val files = vm.waPhoneFiles.value ?: -1
                val count = if (files >= 0) "  $files files" else ""
                binding.waPhoneSize.text = "Phone: ${formatBytes(bytes)}$count"
            }
        }

        vm.waPhoneFiles.observe(viewLifecycleOwner) { files ->
            val bytes = vm.waPhoneBytes.value ?: -1L
            if (bytes >= 0 && files >= 0) {
                binding.waSizeRow.isVisible = true
                binding.waPhoneSize.text = "Phone: ${formatBytes(bytes)}  $files files"
            }
        }

        binding.waBackupNowBtn.setOnClickListener {
            WhatsAppBackupWorker.runNow(requireContext())
            Toast.makeText(requireContext(), "Backup started…", Toast.LENGTH_SHORT).show()
        }

        binding.addFab.setOnClickListener { showAddDialog() }

        var waWasActive = false
        vm.waUploadState.observe(viewLifecycleOwner) { state ->
            val job      = state.activeJob
            val isActive = job != null
            binding.waProgressBar.isVisible  = isActive
            binding.waProgressInfo.isVisible = isActive
            binding.waSpeedRow.isVisible     = isActive && (job?.uploadSpeedBps ?: 0L) > 0

            if (isActive && job != null) {
                val pct = if (job.fileSize > 0)
                    ((job.progressBytes.toFloat() / job.fileSize) * 100).toInt().coerceIn(0, 100)
                else 0
                binding.waProgressBar.max      = 100
                binding.waProgressBar.progress = pct
                binding.waProgressCount.text   = "${state.completed} / ${state.total}"
                binding.waProgressFile.text    = job.fileName
                val speed = job.uploadSpeedBps
                if (speed > 0) binding.waProgressSpeed.text = "${formatBytes(speed)}/s"
            }

            if (waWasActive && !isActive) {
                vm.refreshServerFolders()
            }
            waWasActive = isActive
        }
    }

    private fun updateEmptyState(folders: List<FolderConfig>) {
        val hasAnything = folders.isNotEmpty()
            || binding.waFolderCard.isVisible
            || (connectAdapter.currentList.isNotEmpty())
            || binding.waConnectCard.isVisible
        binding.emptyText.isVisible = !hasAnything
    }

    // ── connecting an existing server folder ──────────────────────────────────

    private fun showConnectFolderDialog(remoteName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Connect \"$remoteName\"")
            .setMessage(
                "This folder already exists on the server.\n\n" +
                "Tap Connect and pick the local folder on your phone that should sync to \"$remoteName\".\n\n" +
                "The app will resume uploading new and changed files from wherever you point it."
            )
            .setPositiveButton("Connect") { _, _ ->
                pendingConnectRemoteName = remoteName
                connectFolderPicker.launch(null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onConnectFolderPicked(uri: Uri, remoteName: String) {
        val ctx = requireContext()
        val doc = DocumentFile.fromTreeUri(ctx, uri)
        val pickedName = doc?.name ?: ""

        if (!pickedName.equals(remoteName, ignoreCase = true)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Wrong folder selected")
                .setMessage(
                    "You selected \"$pickedName\" but this slot syncs to \"$remoteName\" on the server.\n\n" +
                    "Please pick the folder named \"$remoteName\" from your device storage."
                )
                .setPositiveButton("Try again") { _, _ ->
                    pendingConnectRemoteName = remoteName
                    connectFolderPicker.launch(null)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }

        vm.addFolder(
            displayName  = pickedName.ifEmpty { remoteName },
            treeUri      = uri,
            remoteName   = remoteName,
            intervalMins = 60,
            uploadHidden = false
        )
    }

    // ── connecting WA from the server ─────────────────────────────────────────

    private fun showConnectWaDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Connect WhatsApp Backup")
            .setMessage(
                "A WhatsApp backup was found on the server.\n\n" +
                "Tap Connect and pick your WhatsApp folder. You can select:\n" +
                "  • Android › media › com.whatsapp › WhatsApp\n" +
                "  • Android › media › com.whatsapp\n" +
                "  • Android › media\n\n" +
                "The app will create any missing folders automatically."
            )
            .setPositiveButton("Connect") { _, _ ->
                val hint = Uri.parse(
                    "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp"
                )
                connectWaFolderPicker.launch(hint)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onConnectWaFolderPicked(uri: Uri) {
        val ctx = requireContext()
        val pickedDoc = DocumentFile.fromTreeUri(ctx, uri)

        if (pickedDoc == null) {
            Toast.makeText(ctx, "Could not access the selected folder.", Toast.LENGTH_SHORT).show()
            return
        }

        val finalFolder = resolveWhatsAppFolder(pickedDoc)
        if (finalFolder == null) {
            Toast.makeText(
                ctx,
                "Wrong folder selected.\n\nPlease pick:\n  Android › media › com.whatsapp › WhatsApp\n\nOr pick Android › media and the app will create the rest.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(ctx, "Could not get folder permission. Please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            prefs.setWaUri(finalFolder.uri.toString())
            prefs.setWaEnabled(true)

            val hour = prefs.waBackupHour.first()
            WhatsAppBackupWorker.schedule(ctx, hour)

            val repo = SyncRepository.get(ctx)
            val status = repo.getWhatsAppStatus()
            if (status != null && status.hasBackup) {
                showWaRestorePrompt(status.totalFiles, status.totalBytes)
            } else {
                WhatsAppBackupWorker.runNow(ctx)
                Toast.makeText(ctx, "WhatsApp backup connected.", Toast.LENGTH_SHORT).show()
            }

            vm.refreshServerFolders()
        }
    }

    private fun resolveWhatsAppFolder(picked: DocumentFile): DocumentFile? {
        return when (picked.name) {
            "WhatsApp"     -> picked
            "com.whatsapp" -> picked.findFile("WhatsApp") ?: picked.createDirectory("WhatsApp")
            "media" -> {
                val comWhatsapp = picked.findFile("com.whatsapp")
                    ?: picked.createDirectory("com.whatsapp")
                    ?: return null
                comWhatsapp.findFile("WhatsApp") ?: comWhatsapp.createDirectory("WhatsApp")
            }
            else -> null
        }
    }

    private fun showWaRestorePrompt(totalFiles: Int, totalBytes: Long) {
        val sizeMb = totalBytes / 1_048_576
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Backup Found on Server")
            .setMessage(
                "Found $totalFiles files (${sizeMb} MB) on the server.\n\n" +
                "Do you want to restore your WhatsApp data now?\n\n" +
                "Recommended if this is a new phone or fresh install."
            )
            .setPositiveButton("Restore Now") { _, _ ->
                com.simplesync.companion.worker.WhatsAppRestoreWorker.enqueue(requireContext())
                Toast.makeText(requireContext(), "Restore started…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Not Now") { _, _ ->
                WhatsAppBackupWorker.runNow(requireContext())
            }
            .show()
    }

    // ── adding a brand new folder ─────────────────────────────────────────────

    private fun makeIntervalAdapter(): ArrayAdapter<String> {
        val labels = intervalOptions.map { it.first }
        return ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun intervalIndex(minutes: Int): Int {
        val idx = intervalOptions.indexOfFirst { it.second == minutes }
        return if (idx >= 0) idx else intervalOptions.indexOfFirst { it.second >= minutes }.coerceAtLeast(0)
    }

    private fun showAddDialog() {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_folder, null)
        val displayEt  = dialogView.findViewById<EditText>(R.id.etDisplayName)
        val remoteEt   = dialogView.findViewById<EditText>(R.id.etRemoteName)
        val spinner    = dialogView.findViewById<Spinner>(R.id.spinnerInterval)
        val cbHidden   = dialogView.findViewById<android.widget.CheckBox>(R.id.cbUploadHidden)

        spinner.adapter = makeIntervalAdapter()
        spinner.setSelection(intervalOptions.indexOfFirst { it.second == 60 }.coerceAtLeast(0))

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btnChooseFolder).setOnClickListener {
            val display  = displayEt.text.toString().trim()
            val remote   = remoteEt.text.toString().trim()
            val interval = intervalOptions[spinner.selectedItemPosition].second
            val hidden   = cbHidden.isChecked

            if (display.isEmpty() || remote.isEmpty()) {
                Toast.makeText(ctx, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingDisplay  = display
            pendingRemote   = remote
            pendingInterval = interval
            pendingHidden   = hidden
            dialog.dismiss()
            folderPicker.launch(null)
        }

        dialogView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        val bg = dialogBackground(requireContext())
        dialog.window?.setBackgroundDrawable(bg)
        dialogView.background = dialogBackground(requireContext())
    }

    // ── folder settings ───────────────────────────────────────────────────────

    private fun showFolderSettingsSheet(cfg: FolderConfig) {
        val ctx   = requireContext()
        val sheet = BottomSheetDialog(ctx)
        val view  = LayoutInflater.from(ctx).inflate(R.layout.sheet_folder_settings, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.sheetTitle).text = cfg.displayName

        val spinner = view.findViewById<Spinner>(R.id.sheetSpinnerInterval)
        spinner.adapter = makeIntervalAdapter()
        spinner.setSelection(intervalIndex(cfg.scanIntervalMinutes))

        val switchHidden = view.findViewById<SwitchCompat>(R.id.sheetSwitchHidden)
        switchHidden.isChecked = cfg.uploadHiddenFiles

        view.findViewById<MaterialButton>(R.id.sheetSaveBtn).setOnClickListener {
            val newInterval = intervalOptions[spinner.selectedItemPosition].second
            val newHidden   = switchHidden.isChecked
            vm.updateSettings(cfg, newInterval, newHidden)
            sheet.dismiss()
            Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.sheetRemoveBtn).setOnClickListener {
            sheet.dismiss()
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Remove \"${cfg.displayName}\"?")
                .setMessage("This removes the sync configuration. Files already uploaded to the server are not deleted.")
                .setPositiveButton("Remove") { _, _ -> vm.deleteFolder(cfg) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        sheet.show()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.1f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else      -> "$bytes B"
        }
    }

    private fun dialogBackground(ctx: android.content.Context): android.graphics.drawable.GradientDrawable {
        val ta    = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val color = ta.getColor(0, android.graphics.Color.WHITE)
        ta.recycle()
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = ctx.resources.getDimension(R.dimen.dialog_corner_radius)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (prefs.waEnabled.first()) {
                vm.refreshServerFolders()
                vm.refreshWaPhoneSize()  // skips walk if already loaded, see ViewModel
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ── existing configured folders ───────────────────────────────────────────────

class FolderGridAdapter(
    private val onSettings: (FolderConfig) -> Unit,
    private val onToggle:   (FolderConfig) -> Unit
) : ListAdapter<FolderConfig, FolderGridAdapter.VH>(DIFF) {

    inner class VH(val b: ItemFolderGridBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFolderGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cfg = getItem(position)
        val b   = holder.b

        b.displayName.text = cfg.displayName
        b.remoteName.text  = cfg.remoteFolderName

        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        b.lastScan.text = if (cfg.lastScanAt > 0)
            sdf.format(Date(cfg.lastScanAt)) else "Not scanned yet"

        b.activeSwitch.isChecked = cfg.isActive
        b.activeLabel.text = if (cfg.isActive) "Active" else "Inactive"
        b.activeSwitch.setOnClickListener {
            onToggle(cfg)
            val nowActive = b.activeSwitch.isChecked
            b.activeLabel.text = if (nowActive) "Active" else "Inactive"
        }
        b.settingsBtn.setOnClickListener { onSettings(cfg) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<FolderConfig>() {
            override fun areItemsTheSame(a: FolderConfig, b: FolderConfig) = a.id == b.id
            override fun areContentsTheSame(a: FolderConfig, b: FolderConfig) = a == b
        }
    }
}

// ── server folders awaiting connection ───────────────────────────────────────

class ConnectFolderAdapter(
    private val onConnect: (String) -> Unit
) : ListAdapter<String, ConnectFolderAdapter.VH>(DIFF) {

    inner class VH(val b: ItemFolderConnectBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFolderConnectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = getItem(position)
        holder.b.connectFolderName.text = name
        holder.b.connectFolderBtn.setOnClickListener { onConnect(name) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}
