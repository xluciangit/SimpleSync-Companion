package com.simplesync.companion.ui.queue

import android.app.Application
import androidx.lifecycle.*
import androidx.lifecycle.asLiveData
import com.simplesync.companion.data.db.JobStatus
import com.simplesync.companion.data.db.UploadJob
import com.simplesync.companion.repository.SyncRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class QueueViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SyncRepository.get(app)

    val allJobs: LiveData<List<UploadJob>> = repo.allJobsFlow.asLiveData()

    private val _filter = MutableLiveData(FilterType.ALL)
    val filter: LiveData<FilterType> get() = _filter

    val filteredJobs: LiveData<List<UploadJob>> = MediatorLiveData<List<UploadJob>>().apply {
        fun update() {
            val jobs = allJobs.value ?: return
            val f    = _filter.value  ?: FilterType.ALL
            fun List<UploadJob>.sorted() = sortedWith(compareBy(
                // UPLOADING first, then PENDING, then FAILED, then CANCELLED, then COMPLETED
                {
                    when (it.status) {
                        JobStatus.UPLOADING -> 0
                        JobStatus.PENDING   -> 1
                        JobStatus.FAILED    -> 2
                        JobStatus.CANCELLED -> 3
                        JobStatus.COMPLETED -> 4
                    }
                },
                { it.createdAt }  // within same status: oldest first
            ))
            value = when (f) {
                FilterType.ALL       -> jobs.sorted()
                FilterType.PENDING   -> jobs.filter { it.status == JobStatus.PENDING || it.status == JobStatus.UPLOADING }.sorted()
                FilterType.COMPLETED -> jobs.filter { it.status == JobStatus.COMPLETED }.sorted()
                FilterType.FAILED    -> jobs.filter { it.status == JobStatus.FAILED }.sorted()
                FilterType.CANCELLED -> jobs.filter { it.status == JobStatus.CANCELLED }.sorted()
            }
        }
        addSource(allJobs)  { update() }
        addSource(_filter)  { update() }
    }

    val pendingCount: LiveData<Int> = repo.pendingCountFlow.asLiveData()

    // Auto-clear completed jobs 60 seconds after they finish.
    // Runs whenever the job list changes — finds newly-completed jobs
    // whose completedAt is set, waits the remainder of the 60s, then clears.
    private val scheduledClears = mutableSetOf<Long>()  // job IDs already scheduled

    init {
        viewModelScope.launch {
            repo.allJobsFlow.collect { jobs ->
                val now = System.currentTimeMillis()
                jobs.filter { it.status == JobStatus.COMPLETED && it.completedAt != null }
                    .forEach { job ->
                        if (scheduledClears.add(job.id)) {
                            val delay = (60_000L - (now - job.completedAt!!)).coerceAtLeast(0L)
                            launch {
                                delay(delay)
                                repo.clearSingleCompleted(job.id)
                                scheduledClears.remove(job.id)
                            }
                        }
                    }
            }
        }
    }

    fun setFilter(f: FilterType) { _filter.value = f }

    val uploadsPaused: LiveData<Boolean> = repo.prefs.uploadsPaused.asLiveData()

    fun pauseUploads() = viewModelScope.launch {
        repo.prefs.setUploadsPaused(true)
        // Reset any currently UPLOADING job back to PENDING so it retries on resume
        repo.resetStuckUploading()
    }

    fun resumeUploads(context: android.content.Context) = viewModelScope.launch {
        repo.prefs.setUploadsPaused(false)
        // Kick off upload worker immediately on resume
        com.simplesync.companion.worker.UploadWorker.enqueue(context)
    }

    fun retryFailed()       = viewModelScope.launch { repo.retryFailed() }
    fun retryJob(id: Long)  = viewModelScope.launch { repo.retryJob(id) }
    fun cancelJob(id: Long) = viewModelScope.launch { repo.cancelJob(id) }
    fun clearCompleted()    = viewModelScope.launch { repo.clearCompleted() }
}

enum class FilterType { ALL, PENDING, COMPLETED, FAILED, CANCELLED }
