package com.simplesync.companion.ui.folders

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.*
import com.simplesync.companion.data.db.FolderConfig
import com.simplesync.companion.repository.SyncRepository
import com.simplesync.companion.worker.ScanWorker
import kotlinx.coroutines.launch

class FoldersViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SyncRepository.get(app)

    val folders: LiveData<List<FolderConfig>> = repo.foldersFlow.asLiveData()

    fun addFolder(
        displayName: String,
        treeUri: Uri,
        remoteName: String,
        intervalMins: Int,
        uploadHidden: Boolean
    ) = viewModelScope.launch {

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

        val folderId = repo.addFolder(
            FolderConfig(
                displayName         = displayName,
                localUri            = treeUri.toString(),
                remoteFolderName    = remoteName,
                scanIntervalMinutes = intervalMins,
                uploadHiddenFiles   = uploadHidden
            )
        )

        repo.clearJobsForFolder(folderId)
        ScanWorker.runNow(getApplication())
    }

    fun deleteFolder(cfg: FolderConfig) = viewModelScope.launch {
        try {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(cfg.localUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        repo.deleteFolder(cfg)
    }

    fun updateSettings(cfg: FolderConfig, newInterval: Int, uploadHidden: Boolean) = viewModelScope.launch {
        repo.updateFolder(cfg.copy(
            scanIntervalMinutes = newInterval,
            uploadHiddenFiles   = uploadHidden
        ))
        ScanWorker.schedule(getApplication(), newInterval)
    }

    fun toggleActive(cfg: FolderConfig) = viewModelScope.launch {
        repo.updateFolder(cfg.copy(isActive = !cfg.isActive))
    }
}
