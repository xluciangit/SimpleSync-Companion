package com.simplesync.companion.ui.folders

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.*
import com.simplesync.companion.data.db.FolderConfig
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.repository.SyncRepository
import com.simplesync.companion.worker.ScanWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.simplesync.companion.data.db.JobStatus
import com.simplesync.companion.data.db.UploadJob

class FoldersViewModel(app: Application) : AndroidViewModel(app) {

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val repo  = SyncRepository.get(app)
    private val prefs = Prefs.get(app)

    val folders: LiveData<List<FolderConfig>> = repo.foldersFlow.asLiveData()

    // server folders not yet linked to a local config — shown as "Connect" cards
    private val _unconnected = MutableLiveData<List<String>>(emptyList())
    val unconnectedServerFolders: LiveData<List<String>> = _unconnected

    private val _waOnServer = MutableLiveData(false)
    val waBackupOnServer: LiveData<Boolean> = _waOnServer

    private val _waServerBytes = MutableLiveData(-1L)
    val waServerBytes: LiveData<Long> = _waServerBytes

    private val _waServerFiles = MutableLiveData(-1)
    val waServerFiles: LiveData<Int> = _waServerFiles

    private val _waLastBackup = MutableLiveData<String?>("")
    val waLastBackup: LiveData<String?> = _waLastBackup

    private val _waPhoneBytes = MutableLiveData(-1L)
    val waPhoneBytes: LiveData<Long> = _waPhoneBytes

    private val _waPhoneFiles = MutableLiveData(-1)
    val waPhoneFiles: LiveData<Int> = _waPhoneFiles

    data class WaUploadState(
        val activeJob: UploadJob?,
        val completed: Int,
        val total: Int
    )

    val waUploadState: LiveData<WaUploadState> = repo.allJobsFlow
        .map { jobs ->
            val waJobs = jobs.filter { it.folderConfigId == com.simplesync.companion.repository.SyncRepository.WA_FOLDER_ID }
            val active = waJobs.firstOrNull {
                it.status == JobStatus.UPLOADING || it.status == JobStatus.HASHING
            }
            val completed = waJobs.count {
                it.status == JobStatus.COMPLETED || it.status == JobStatus.SKIPPED
            }
            WaUploadState(active, completed, waJobs.size)
        }
        .asLiveData()

    init {
        refreshServerFolders()
        viewModelScope.launch {
            // drop(1) skips the initial emission so we only react to actual changes
            prefs.waLastScanAt.drop(1).collect {
                refreshServerFolders()
                refreshWaPhoneSize(force = true)
            }
        }
    }

    fun refreshServerFolders() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val serverFolders = repo.getServerFolders().map { it.name }
            val localNames    = repo.foldersFlow.first().map { it.remoteFolderName }
            _unconnected.postValue(serverFolders.filter { it !in localNames })

            val waStatus = repo.getWhatsAppStatus()
            _waOnServer.postValue(waStatus?.hasBackup == true)
            if (waStatus != null) {
                _waServerBytes.postValue(waStatus.totalBytes)
                _waServerFiles.postValue(waStatus.totalFiles)
                // null lastBackup from server = genuinely no backup yet
                _waLastBackup.postValue(waStatus.lastBackup)
            }
            // if waStatus is null (network error) we leave _waLastBackup as-is
            // so a failed refresh doesn't wipe a value that was already showing
        }
    }

    fun refreshWaPhoneSize(force: Boolean = false) = viewModelScope.launch {
        if (!force && _waPhoneBytes.value != -1L) return@launch
        val uriStr = prefs.waUri.first()
        if (uriStr.isEmpty()) return@launch
        withContext(Dispatchers.IO) {
            val treeDoc = DocumentFile.fromTreeUri(getApplication(), Uri.parse(uriStr))
                ?: return@withContext
            var totalBytes = 0L
            var totalFiles = 0
            fun walk(doc: DocumentFile) {
                for (child in doc.listFiles()) {
                    if (child.isDirectory) walk(child) else { totalBytes += child.length(); totalFiles++ }
                }
            }
            walk(treeDoc)
            _waPhoneBytes.postValue(totalBytes)
            _waPhoneFiles.postValue(totalFiles)
        }
    }

    fun addFolder(
        displayName: String,
        treeUri: Uri,
        remoteName: String,
        intervalMins: Int,
        uploadHidden: Boolean
    ) = viewModelScope.launch(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }

        if (repo.folderExistsByRemoteName(remoteName)) {
            _error.postValue("A folder syncing to \"$remoteName\" on the server already exists.")
            return@launch
        }

        val folderId = repo.addFolder(
            FolderConfig(
                displayName         = displayName,
                localUri            = treeUri.toString(),
                remoteFolderName    = remoteName,
                scanIntervalMinutes = intervalMins,
                uploadHiddenFiles   = uploadHidden
            )
        )

        if (folderId <= 0L) {
            _error.postValue("Could not add folder — \"$remoteName\" may already be configured.")
            return@launch
        }

        repo.clearJobsForFolder(folderId)
        ScanWorker.runNow(getApplication())

        refreshServerFolders()
    }

    fun deleteFolder(cfg: FolderConfig) = viewModelScope.launch(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(cfg.localUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        repo.deleteFolder(cfg)
        refreshServerFolders()
    }

    fun updateSettings(cfg: FolderConfig, newInterval: Int, uploadHidden: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        repo.updateFolder(cfg.copy(
            scanIntervalMinutes = newInterval,
            uploadHiddenFiles   = uploadHidden
        ))
        ScanWorker.schedule(getApplication(), newInterval)
    }

    fun toggleActive(cfg: FolderConfig) = viewModelScope.launch(Dispatchers.IO) {
        repo.updateFolder(cfg.copy(isActive = !cfg.isActive))
    }
}
