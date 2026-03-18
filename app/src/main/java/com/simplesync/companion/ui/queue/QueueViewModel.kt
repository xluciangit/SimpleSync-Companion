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

    private val _filter = MutableLiveData(FilterType.ALL)
    val filter: LiveData<FilterType> get() = _filter

    val filteredJobs: LiveData<List<UploadJob>> = MediatorLiveData<List<UploadJob>>().apply {
        fun update() {
            val jobs = allJobs.value ?: return
            val f = _filter.value ?: FilterType.ALL
            fun List<UploadJob>.sorted() = sortedWith(compareBy(
                {
                    when (it.status) {
                        JobStatus.UPLOADING -> 0
                        JobStatus.PENDING   -> 1
                        JobStatus.FAILED    -> 2
                        JobStatus.CANCELLED -> 3
                        JobStatus.COMPLETED -> 4
                        JobStatus.SKIPPED   -> 4
                    }
                },
                { it.createdAt }
            ))
            value = when (f) {
                FilterType.ALL       -> jobs.sorted()
                FilterType.PENDING   -> jobs.filter {
                    it.status == JobStatus.PENDING || it.status == JobStatus.UPLOADING
                }.sorted()
                FilterType.COMPLETED -> jobs.filter {
                    it.status == JobStatus.COMPLETED || it.status == JobStatus.SKIPPED
                }.sorted()
                FilterType.FAILED    -> jobs.filter { it.status == JobStatus.FAILED }.sorted()
                FilterType.CANCELLED -> jobs.filter { it.status == JobStatus.CANCELLED }.sorted()
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

enum class FilterType { ALL, PENDING, COMPLETED, FAILED, CANCELLED }
