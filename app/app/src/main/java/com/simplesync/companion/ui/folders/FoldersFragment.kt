package com.simplesync.companion.ui.folders

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import com.simplesync.companion.R
import com.simplesync.companion.data.db.FolderConfig
import com.simplesync.companion.databinding.FragmentFoldersBinding
import com.simplesync.companion.databinding.ItemFolderConfigBinding
import java.text.SimpleDateFormat
import java.util.*

class FoldersFragment : Fragment() {

    private var _binding: FragmentFoldersBinding? = null
    private val binding get() = _binding!!
    private val vm: FoldersViewModel by viewModels()
    private lateinit var adapter: FolderAdapter

    private var pendingDisplay  = ""
    private var pendingRemote   = ""
    private var pendingInterval = 60
    private var pendingHidden   = false

    private val intervalOptions: List<Pair<String, Int>> = buildList {
        listOf(15, 30, 45, 60).forEach { add("$it minutes" to it) }
        (2..24).forEach { add("$it hours" to it * 60) }
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        vm.addFolder(pendingDisplay, uri, pendingRemote, pendingInterval, pendingHidden)
        Toast.makeText(requireContext(), "Folder added – first scan starting", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentFoldersBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FolderAdapter(
            onSettings = { cfg -> showFolderSettingsSheet(cfg) },
            onToggle   = { vm.toggleActive(it) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter  = this@FoldersFragment.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        vm.folders.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.emptyText.isVisible = list.isEmpty()
        }

        binding.addFab.setOnClickListener { showAddDialog() }
    }

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
        val ctx        = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_folder, null)
        val displayEt  = dialogView.findViewById<EditText>(R.id.etDisplayName)
        val remoteEt   = dialogView.findViewById<EditText>(R.id.etRemoteName)
        val spinner    = dialogView.findViewById<Spinner>(R.id.spinnerInterval)
        val cbHidden   = dialogView.findViewById<CheckBox>(R.id.cbUploadHidden)

        spinner.adapter = makeIntervalAdapter()
        spinner.setSelection(intervalOptions.indexOfFirst { it.second == 60 }.coerceAtLeast(0))

        AlertDialog.Builder(ctx)
            .setTitle("Add Sync Folder")
            .setView(dialogView)
            .setPositiveButton("Choose Folder") { _, _ ->
                val display  = displayEt.text.toString().trim()
                val remote   = remoteEt.text.toString().trim()
                val interval = intervalOptions[spinner.selectedItemPosition].second
                val hidden   = cbHidden.isChecked

                if (display.isEmpty() || remote.isEmpty()) {
                    Toast.makeText(ctx, "Please fill all fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingDisplay  = display
                pendingRemote   = remote
                pendingInterval = interval
                pendingHidden   = hidden
                folderPicker.launch(null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Folder settings bottom sheet ──────────────────────────────────────────
    private fun showFolderSettingsSheet(cfg: FolderConfig) {
        val ctx    = requireContext()
        val sheet  = BottomSheetDialog(ctx)
        val view   = LayoutInflater.from(ctx).inflate(R.layout.sheet_folder_settings, null)
        sheet.setContentView(view)

        // Title
        view.findViewById<TextView>(R.id.sheetTitle).text = cfg.displayName

        // Interval spinner
        val spinner = view.findViewById<Spinner>(R.id.sheetSpinnerInterval)
        spinner.adapter = makeIntervalAdapter()
        spinner.setSelection(intervalIndex(cfg.scanIntervalMinutes))

        // Hidden files toggle
        val switchHidden = view.findViewById<SwitchCompat>(R.id.sheetSwitchHidden)
        switchHidden.isChecked = cfg.uploadHiddenFiles

        // Save button
        view.findViewById<MaterialButton>(R.id.sheetSaveBtn).setOnClickListener {
            val newInterval = intervalOptions[spinner.selectedItemPosition].second
            val newHidden   = switchHidden.isChecked
            vm.updateSettings(cfg, newInterval, newHidden)
            sheet.dismiss()
            Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        // Remove button
        view.findViewById<MaterialButton>(R.id.sheetRemoveBtn).setOnClickListener {
            sheet.dismiss()
            AlertDialog.Builder(ctx)
                .setTitle("Remove \"${cfg.displayName}\"?")
                .setMessage("This removes the sync configuration. Files already uploaded to the server are not deleted.")
                .setPositiveButton("Remove") { _, _ -> vm.deleteFolder(cfg) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        sheet.show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ── Adapter ───────────────────────────────────────────────────────────────────
class FolderAdapter(
    private val onSettings: (FolderConfig) -> Unit,
    private val onToggle:   (FolderConfig) -> Unit
) : ListAdapter<FolderConfig, FolderAdapter.VH>(DIFF) {

    inner class VH(val b: ItemFolderConfigBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFolderConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cfg = getItem(position)
        val b   = holder.b

        b.displayName.text = cfg.displayName
        b.remoteName.text  = "→ Server: ${cfg.remoteFolderName}"

        val intervalLabel = when {
            cfg.scanIntervalMinutes < 60  -> "${cfg.scanIntervalMinutes} min"
            cfg.scanIntervalMinutes == 60 -> "1 hour"
            cfg.scanIntervalMinutes % 60 == 0 -> "${cfg.scanIntervalMinutes / 60} hours"
            else -> "${cfg.scanIntervalMinutes} min"
        }
        val hiddenLabel = if (cfg.uploadHiddenFiles) " · hidden ✓" else ""
        b.intervalText.text = "Scan every $intervalLabel$hiddenLabel"

        b.activeSwitch.isChecked = cfg.isActive

        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        b.lastScan.text = if (cfg.lastScanAt > 0)
            "Last scan: ${sdf.format(Date(cfg.lastScanAt))}" else "Not scanned yet"

        b.activeSwitch.setOnClickListener { onToggle(cfg) }
        b.settingsBtn.setOnClickListener  { onSettings(cfg) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<FolderConfig>() {
            override fun areItemsTheSame(a: FolderConfig, b: FolderConfig) = a.id == b.id
            override fun areContentsTheSame(a: FolderConfig, b: FolderConfig) = a == b
        }
    }
}
