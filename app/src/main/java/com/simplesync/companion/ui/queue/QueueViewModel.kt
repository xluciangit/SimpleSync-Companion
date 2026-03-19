package com.simplesync.companion.ui.queue

import android.app.Application
import androidx.lifecycle.*
import androidx.lifecycle.asLiveData
import com.simplesync.companion.data.db.JobStatus
import com.simplesync.companion.data.db.UploadJob
import com.simplesync.companion.repository.SyncRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class QueueViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SyncRepository.get(app)

    val allJobs: LiveData<List<UploadJob>> = repo.allJobsFlow.asLiveData()

    private val _filter = MutableLiveData(FilterType.UPLOADING)
    val filter: LiveData<FilterType> get() = _filter

    val filteredJobs: LiveData<List<UploadJob>> = MediatorLiveData<List<UploadJob>>().apply {
        fun update() {
            val jobs = allJobs.value ?: return
            val f = _filter.value ?: FilterType.UPLOADING
            value = when (f) {
                FilterType.UPLOADING -> jobs.filter {
                    it.status == JobStatus.HASHING || it.status == JobStatus.UPLOADING || it.status == JobStatus.PENDING
                }.sortedWith(compareBy(
                    {
                        when (it.status) {
                            JobStatus.HASHING   -> 0
                            JobStatus.UPLOADING -> 1
                            JobStatus.PENDING   -> 2
                            else                -> 3
                        }
                    },
                    { it.createdAt }
                ))
                FilterType.COMPLETED -> jobs.filter {
                    it.status == JobStatus.COMPLETED || it.status == JobStatus.SKIPPED
                }.sortedByDescending { it.completedAt }
                FilterType.FAILED    -> jobs.filter { it.status == JobStatus.FAILED }.sortedByDescending { it.createdAt }
                FilterType.CANCELLED -> jobs.filter { it.status == JobStatus.CANCELLED }.sortedByDescending { it.createdAt }
            }
        }
        addSource(allJobs)  { update() }
        addSource(_filter)  { update() }
    }

    val pendingCount: LiveData<Int> = repo.pendingCountFlow.asLiveData()

    private val scheduledClears = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            repo.allJobsFlow.collect { jobs ->
                val now = System.currentTimeMillis()
                jobs.filter {
                    (it.status == JobStatus.COMPLETED || it.status == JobStatus.SKIPPED)
                        && it.completedAt != null
                }.forEach { job ->
                    if (scheduledClears.add(job.id)) {
                        val delay = (600_000L - (now - job.completedAt!!)).coerceAtLeast(0L)
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
        repo.resetStuckUploading()
    }

    fun resumeUploads(context: android.content.Context) = viewModelScope.launch {
        repo.prefs.setUploadsPaused(false)
        com.simplesync.companion.worker.UploadWorker.enqueue(context, replace = true)
    }

    fun retryFailed()       = viewModelScope.launch { repo.retryFailed() }
    fun retryJob(id: Long)  = viewModelScope.launch { repo.retryJob(id) }
    fun cancelJob(id: Long) = viewModelScope.launch { repo.cancelJob(id) }
    fun clearCompleted()    = viewModelScope.launch { repo.clearCompleted() }
    fun clearCancelled()    = viewModelScope.launch { repo.clearCancelled() }
}

enum class FilterType { UPLOADING, COMPLETED, FAILED, CANCELLED }
