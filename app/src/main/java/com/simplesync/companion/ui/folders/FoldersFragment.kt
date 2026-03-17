package com.simplesync.companion.ui.folders

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import com.simplesync.companion.R
import com.simplesync.companion.data.db.FolderConfig
import com.simplesync.companion.databinding.FragmentFoldersBinding
import com.simplesync.companion.databinding.ItemFolderGridBinding
import java.text.SimpleDateFormat
import java.util.*

class FoldersFragment : Fragment() {

    private var _binding: FragmentFoldersBinding? = null
    private val binding get() = _binding!!
    private val vm: FoldersViewModel by viewModels()
    private lateinit var adapter: FolderGridAdapter

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
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentFoldersBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FolderGridAdapter(
            onSettings = { cfg -> showFolderSettingsSheet(cfg) },
            onToggle   = { vm.toggleActive(it) }
        )
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(context, 2)
            this.adapter  = this@FoldersFragment.adapter
        }

        var prevCount = -1
        vm.folders.observe(viewLifecycleOwner) { list ->
            if (prevCount >= 0 && list.size > prevCount) {
                Toast.makeText(requireContext(), "Folder added – first scan starting", Toast.LENGTH_SHORT).show()
            }
            prevCount = list.size
            adapter.submitList(list)
            binding.emptyText.isVisible = list.isEmpty()
        }

        vm.error.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
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
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_folder, null)
        val displayEt  = dialogView.findViewById<EditText>(R.id.etDisplayName)
        val remoteEt = dialogView.findViewById<EditText>(R.id.etRemoteName)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerInterval)
        val cbHidden = dialogView.findViewById<android.widget.CheckBox>(R.id.cbUploadHidden)

        spinner.adapter = makeIntervalAdapter()
        spinner.setSelection(intervalOptions.indexOfFirst { it.second == 60 }.coerceAtLeast(0))

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btnChooseFolder).setOnClickListener {
            val display  = displayEt.text.toString().trim()
            val remote = remoteEt.text.toString().trim()
            val interval = intervalOptions[spinner.selectedItemPosition].second
            val hidden = cbHidden.isChecked

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

    private fun showFolderSettingsSheet(cfg: FolderConfig) {
        val ctx = requireContext()
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
            val newHidden = switchHidden.isChecked
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

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
    private fun dialogBackground(ctx: android.content.Context): android.graphics.drawable.GradientDrawable {
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val color = ta.getColor(0, android.graphics.Color.WHITE)
        ta.recycle()
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = ctx.resources.getDimension(R.dimen.dialog_corner_radius)
        }
    }
}

class FolderGridAdapter(
    private val onSettings: (FolderConfig) -> Unit,
    private val onToggle:   (FolderConfig) -> Unit
) : ListAdapter<FolderConfig, FolderGridAdapter.VH>(DIFF) {

    inner class VH(val b: ItemFolderGridBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFolderGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cfg = getItem(position)
        val b = holder.b

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
        b.settingsBtn.setOnClickListener  { onSettings(cfg) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<FolderConfig>() {
            override fun areItemsTheSame(a: FolderConfig, b: FolderConfig) = a.id == b.id
            override fun areContentsTheSame(a: FolderConfig, b: FolderConfig) = a == b
        }
    }

}
