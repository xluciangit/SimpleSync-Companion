package com.simplesync.companion.ui.queue

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.*
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.simplesync.companion.R
import com.simplesync.companion.data.db.JobStatus
import com.simplesync.companion.data.db.UploadJob
import com.simplesync.companion.databinding.FragmentQueueBinding
import com.simplesync.companion.databinding.ItemUploadJobBinding
import com.simplesync.companion.worker.UploadWorker
import java.text.SimpleDateFormat
import java.util.*

class QueueFragment : Fragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!
    private val vm: QueueViewModel by viewModels()
    private lateinit var adapter: JobAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentQueueBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = JobAdapter(
            onRetry  = { vm.retryJob(it.id);  UploadWorker.enqueue(requireContext()) },
            onCancel = { vm.cancelJob(it.id) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter  = this@QueueFragment.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        // Filter chips
        val chipMap = mapOf(
            FilterType.ALL       to binding.chipAll,
            FilterType.PENDING   to binding.chipPending,
            FilterType.COMPLETED to binding.chipCompleted,
            FilterType.FAILED    to binding.chipFailed,
            FilterType.CANCELLED to binding.chipCancelled
        )
        chipMap.forEach { (f, chip) ->
            chip.setOnClickListener { vm.setFilter(f) }
        }

        vm.filter.observe(viewLifecycleOwner) { f ->
            chipMap.forEach { (type, chip) -> chip.isChecked = type == f }
        }

        vm.filteredJobs.observe(viewLifecycleOwner) { jobs ->
            // Capture scroll state BEFORE submitting the new list.
            // We only want to scroll to top if the user is already near the top
            // (within the first item) — never auto-scroll when they've scrolled down.
            val lm = binding.recyclerView.layoutManager as LinearLayoutManager
            val firstVisible = lm.findFirstCompletelyVisibleItemPosition()

            adapter.submitList(jobs) {
                // This callback runs after DiffUtil has been applied.
                // Scroll to top only if we were already at the top.
                if (firstVisible <= 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
                binding.emptyText.isVisible = jobs.isEmpty()
            }
        }

        vm.pendingCount.observe(viewLifecycleOwner) { count ->
            binding.pendingBadge.isVisible = count > 0
            binding.pendingBadge.text = "$count pending"
        }

        // Pause / Resume toggle
        // Use Assist chip style (always looks enabled) and tint background manually
        // to show active state without the Filter chip's disabled-looking unchecked state.
        vm.uploadsPaused.observe(viewLifecycleOwner) { paused ->
            binding.pauseResumeBtn.text = if (paused) "▶  Resume" else "⏸  Pause"
            // Filled accent colour when paused (Resume), neutral surface when active (Pause)
            val bgColor = if (paused)
                com.google.android.material.color.MaterialColors.getColor(
                    binding.pauseResumeBtn,
                    com.google.android.material.R.attr.colorPrimaryContainer
                )
            else
                com.google.android.material.color.MaterialColors.getColor(
                    binding.pauseResumeBtn,
                    com.google.android.material.R.attr.colorSurfaceVariant
                )
            binding.pauseResumeBtn.chipBackgroundColor =
                android.content.res.ColorStateList.valueOf(bgColor)
        }
        binding.pauseResumeBtn.setOnClickListener {
            val paused = vm.uploadsPaused.value ?: false
            if (paused) vm.resumeUploads(requireContext()) else vm.pauseUploads()
        }

        binding.retryFailedBtn.setOnClickListener {
            vm.retryFailed()
            UploadWorker.enqueue(requireContext())
        }
        binding.clearCompletedBtn.setOnClickListener { vm.clearCompleted() }
        binding.clearCancelledBtn.setOnClickListener { vm.clearCancelled() }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ── Adapter ───────────────────────────────────────────────────────────────────
class JobAdapter(
    private val onRetry:  (UploadJob) -> Unit,
    private val onCancel: (UploadJob) -> Unit
) : ListAdapter<UploadJob, JobAdapter.VH>(DIFF) {

    inner class VH(val b: ItemUploadJobBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemUploadJobBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    // Full bind — called when item first appears or status/content changes
    override fun onBindViewHolder(holder: VH, position: Int) {
        val job = getItem(position)
        val b   = holder.b
        b.fileName.text   = job.fileName
        b.fileSize.text   = fmtBytes(job.fileSize)
        b.folderName.text = job.remoteFolderName

        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        b.createdAt.text  = sdf.format(Date(job.createdAt))

        // Status pill — text + bg tint
        data class StatusStyle(val label: String, val textHex: Int, val bgHex: Int)
        val style = when (job.status) {
            JobStatus.PENDING   -> StatusStyle("Pending",   0xFF6B7280.toInt(), 0xFFF3F4F6.toInt())
            JobStatus.UPLOADING -> StatusStyle("Uploading", 0xFF2563EB.toInt(), 0xFFEFF6FF.toInt())
            JobStatus.COMPLETED -> StatusStyle("Completed", 0xFF16A34A.toInt(), 0xFFDCFCE7.toInt())
            JobStatus.FAILED    -> StatusStyle("Failed",    0xFFDC2626.toInt(), 0xFFFEE2E2.toInt())
            JobStatus.CANCELLED -> StatusStyle("Cancelled", 0xFF9CA3AF.toInt(), 0xFFF9FAFB.toInt())
        }
        b.status.text = style.label
        b.status.setTextColor(style.textHex)
        (b.status.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(style.bgHex)

        b.uploadProgressRow.isVisible = job.status == JobStatus.UPLOADING
        bindProgress(b, job)

        b.errorMessage.isVisible = job.errorMessage != null
        b.errorMessage.text      = job.errorMessage

        b.retryBtn.isVisible  = job.status == JobStatus.FAILED
        b.cancelBtn.isVisible = job.status == JobStatus.PENDING

        b.retryBtn.setOnClickListener  { onRetry(job) }
        b.cancelBtn.setOnClickListener { onCancel(job) }
    }

    // Partial bind — called with PROGRESS payload to update only the progress views,
    // skipping the full rebind that causes the card flash.
    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        if (payloads.any { it == PAYLOAD_PROGRESS }) {
            bindProgress(holder.b, getItem(position))
        }
    }

    private fun bindProgress(b: ItemUploadJobBinding, job: UploadJob) {
        if (job.status != JobStatus.UPLOADING) return
        val pct = if (job.fileSize > 0)
            ((job.progressBytes.toFloat() / job.fileSize) * 100).toInt().coerceIn(0, 100)
        else 0
        b.uploadProgressBar.progress = pct
        b.uploadPct.text  = "$pct%"
        b.uploadSpeed.text = if (job.uploadSpeedBps > 0) " · ${fmtSpeed(job.uploadSpeedBps)}" else ""
    }

    companion object {
        const val PAYLOAD_PROGRESS = "progress"

        val DIFF = object : DiffUtil.ItemCallback<UploadJob>() {
            override fun areItemsTheSame(a: UploadJob, b: UploadJob) = a.id == b.id
            override fun areContentsTheSame(a: UploadJob, b: UploadJob) = a == b

            // When only progress/speed changed on an UPLOADING job, emit a lightweight
            // payload instead of returning false (which would trigger a full rebind + flash).
            override fun getChangePayload(oldItem: UploadJob, newItem: UploadJob): Any? {
                if (oldItem.status == JobStatus.UPLOADING &&
                    newItem.status == JobStatus.UPLOADING &&
                    oldItem.progressBytes != newItem.progressBytes
                ) return PAYLOAD_PROGRESS
                return null  // fall through to full rebind for all other changes
            }
        }

        fun fmtBytes(b: Long): String = when {
            b < 1024          -> "$b B"
            b < 1_048_576     -> "%.1f KB".format(b / 1024f)
            b < 1_073_741_824 -> "%.1f MB".format(b / 1_048_576f)
            else              -> "%.2f GB".format(b / 1_073_741_824f)
        }

        fun fmtSpeed(bps: Long): String = when {
            bps < 1024          -> "$bps B/s"
            bps < 1_048_576     -> "%.1f KB/s".format(bps / 1024f)
            bps < 1_073_741_824 -> "%.1f MB/s".format(bps / 1_048_576f)
            else                -> "%.2f GB/s".format(bps / 1_073_741_824f)
        }
    }
}
