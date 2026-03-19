package com.simplesync.companion.ui.queue

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.*
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

        val chipMap = mapOf(
            FilterType.UPLOADING  to binding.chipUploading,
            FilterType.COMPLETED  to binding.chipCompleted,
            FilterType.FAILED     to binding.chipFailed,
            FilterType.CANCELLED  to binding.chipCancelled
        )
        chipMap.forEach { (f, chip) ->
            chip.setOnClickListener { vm.setFilter(f) }
        }

        vm.filter.observe(viewLifecycleOwner) { f ->
            chipMap.forEach { (type, chip) -> chip.isChecked = type == f }
        }

        vm.filteredJobs.observe(viewLifecycleOwner) { jobs ->
            adapter.submitList(jobs) {
                binding.emptyText.isVisible = jobs.isEmpty()
            }
        }

        vm.pendingCount.observe(viewLifecycleOwner) { count ->
            binding.pendingBadge.isVisible = count > 0
            binding.pendingBadge.text = "$count pending"
        }

        vm.uploadsPaused.observe(viewLifecycleOwner) { paused ->
            if (paused) {
                binding.pauseResumeBtn.text = "Resume"
                binding.pauseResumeBtn.setIconResource(R.drawable.ic_resume)
            } else {
                binding.pauseResumeBtn.text = "Pause"
                binding.pauseResumeBtn.setIconResource(R.drawable.ic_pause)
            }
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
class JobAdapter(
    private val onRetry:  (UploadJob) -> Unit,
    private val onCancel: (UploadJob) -> Unit
) : ListAdapter<UploadJob, JobAdapter.VH>(DIFF) {

    inner class VH(val b: ItemUploadJobBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemUploadJobBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val job = getItem(position)
        val b = holder.b
        b.fileName.text   = job.fileName
        b.fileSize.text   = fmtBytes(job.fileSize)
        b.folderName.text = job.remoteFolderName
        b.filePath.text   = job.relativePath
        b.uploadNote.isVisible = job.uploadNote != null
        b.uploadNote.text      = job.uploadNote ?: ""

        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        b.createdAt.text  = sdf.format(Date(job.createdAt))

        data class StatusStyle(val label: String, val textHex: Int, val bgHex: Int)
        val style = when (job.status) {
            JobStatus.PENDING   -> StatusStyle("Pending",          0xFF6B7280.toInt(), 0xFFF3F4F6.toInt())
            JobStatus.HASHING   -> StatusStyle("Hashing",          0xFF0E7490.toInt(), 0xFFECFEFF.toInt())
            JobStatus.UPLOADING -> StatusStyle("Uploading",        0xFF2563EB.toInt(), 0xFFEFF6FF.toInt())
            JobStatus.COMPLETED -> StatusStyle("Completed",        0xFF16A34A.toInt(), 0xFFDCFCE7.toInt())
            JobStatus.SKIPPED   -> StatusStyle("Already on server",0xFF7C3AED.toInt(), 0xFFEDE9FE.toInt())
            JobStatus.FAILED    -> StatusStyle("Failed",           0xFFDC2626.toInt(), 0xFFFEE2E2.toInt())
            JobStatus.CANCELLED -> StatusStyle("Cancelled",        0xFF9CA3AF.toInt(), 0xFFF9FAFB.toInt())
        }
        b.status.text = style.label
        b.status.setTextColor(style.textHex)
        (b.status.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(style.bgHex)

        b.uploadProgressRow.isVisible = job.status == JobStatus.UPLOADING || job.status == JobStatus.HASHING
        bindProgress(b, job)

        b.errorMessage.isVisible = job.errorMessage != null
        b.errorMessage.text      = job.errorMessage

        b.retryBtn.isVisible  = job.status == JobStatus.FAILED
        b.cancelBtn.isVisible = job.status == JobStatus.PENDING

        b.retryBtn.setOnClickListener  { onRetry(job) }
        b.cancelBtn.setOnClickListener { onCancel(job) }
    }

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
        if (job.status != JobStatus.UPLOADING && job.status != JobStatus.HASHING) return
        val pct = if (job.fileSize > 0)
            ((job.progressBytes.toFloat() / job.fileSize) * 100).toInt().coerceIn(0, 100)
        else 0
        b.uploadProgressBar.progress = pct
        b.uploadPct.text   = "$pct%"
        b.uploadSpeed.text = if (job.status == JobStatus.UPLOADING && job.uploadSpeedBps > 0)
            " · ${fmtSpeed(job.uploadSpeedBps)}" else ""
    }

    companion object {
        const val PAYLOAD_PROGRESS = "progress"

        val DIFF = object : DiffUtil.ItemCallback<UploadJob>() {
            override fun areItemsTheSame(a: UploadJob, b: UploadJob) = a.id == b.id
            override fun areContentsTheSame(a: UploadJob, b: UploadJob) = a == b

            override fun getChangePayload(oldItem: UploadJob, newItem: UploadJob): Any? {
                val activeStatus = newItem.status == JobStatus.UPLOADING || newItem.status == JobStatus.HASHING
                if (activeStatus && oldItem.status == newItem.status &&
                    oldItem.progressBytes != newItem.progressBytes
                ) return PAYLOAD_PROGRESS
                return null
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
