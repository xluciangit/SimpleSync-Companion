package com.simplesync.companion.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.simplesync.companion.data.db.*
import com.simplesync.companion.data.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SyncRepository(private val context: Context) {

    private val db      = AppDatabase.get(context)
    private val cfgDao  = db.folderConfigDao()
    private val trkDao  = db.trackedFileDao()
    private val jobDao  = db.uploadJobDao()
    val prefs           = Prefs.get(context)

    // ── Folder configs ────────────────────────────────────────────────────────
    val foldersFlow: Flow<List<FolderConfig>> = cfgDao.getAllFlow()

    suspend fun addFolder(cfg: FolderConfig): Long = cfgDao.insert(cfg)
    suspend fun updateFolder(cfg: FolderConfig) = cfgDao.update(cfg)
    suspend fun deleteFolder(cfg: FolderConfig) {
        cfgDao.delete(cfg)
        trkDao.deleteForConfig(cfg.id)
        jobDao.deleteForConfig(cfg.id)
    }
    suspend fun getFolder(id: Long) = cfgDao.getById(id)

    // ── Upload jobs ───────────────────────────────────────────────────────────
    val allJobsFlow: Flow<List<UploadJob>> = jobDao.getAllFlow()
    val pendingCountFlow: Flow<Int>        = jobDao.pendingCountFlow()

    suspend fun retryFailed()          = jobDao.retryAllFailed()
    suspend fun retryJob(id: Long)     = jobDao.retryJob(id)
    suspend fun cancelJob(id: Long)    = jobDao.cancelJob(id)
    suspend fun clearCompleted()              = jobDao.clearCompleted()
    suspend fun clearSingleCompleted(id: Long) = jobDao.clearSingleCompleted(id)
    suspend fun resetStuckUploading()  = jobDao.resetStuckUploading()

    suspend fun clearJobsForFolder(folderId: Long) = jobDao.deleteForConfig(folderId)

    // ── Scan logic ────────────────────────────────────────────────────────────
    suspend fun scanAndQueue(config: FolderConfig): Int = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(config.localUri)
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        var newJobs = 0

        suspend fun traverse(doc: DocumentFile, relativeDir: String) {
            for (child in doc.listFiles()) {
                val childName = child.name ?: continue

                // Skip hidden files/folders (names starting with '.') unless enabled
                if (!config.uploadHiddenFiles && childName.startsWith(".")) continue

                val relPath = if (relativeDir.isEmpty()) childName else "$relativeDir/$childName"

                if (child.isDirectory) { traverse(child, relPath); continue }

                val lastMod  = child.lastModified()
                val size     = child.length()
                val existing = trkDao.find(config.id, relPath)

                val isNew     = existing == null
                val isChanged = existing != null &&
                    (existing.lastModified != lastMod || existing.fileSize != size)

                if (isNew || isChanged) {
                    val activeJob = jobDao.findActive(config.id, relPath)
                    if (activeJob != null) continue

                    val inserted = jobDao.insert(
                        UploadJob(
                            folderConfigId   = config.id,
                            remoteFolderName = config.remoteFolderName,
                            relativePath     = relPath,
                            fileUriString    = child.uri.toString(),
                            fileName         = childName,
                            fileSize         = size
                        )
                    )
                    if (inserted != -1L) newJobs++
                }
            }
        }

        traverse(treeDoc, "")
        cfgDao.updateLastScan(config.id, System.currentTimeMillis())
        newJobs
    }

    suspend fun markUploaded(job: UploadJob, sha256Hash: String? = null) {
        trkDao.upsert(
            TrackedFile(
                folderConfigId = job.folderConfigId,
                relativePath   = job.relativePath,
                lastModified   = 0L,
                fileSize       = job.fileSize,
                sha256Hash     = sha256Hash
            )
        )
        try {
            val doc = DocumentFile.fromSingleUri(context, Uri.parse(job.fileUriString))
            if (doc != null) {
                trkDao.upsert(
                    TrackedFile(
                        folderConfigId = job.folderConfigId,
                        relativePath   = job.relativePath,
                        lastModified   = doc.lastModified(),
                        fileSize       = doc.length(),
                        sha256Hash     = sha256Hash
                    )
                )
            }
        } catch (_: Exception) {}
    }

    suspend fun getPendingJobs() = jobDao.getPending()

    suspend fun updateJobStatus(id: Long, status: JobStatus, err: String? = null, prog: Long = 0) {
        val ts = if (status == JobStatus.COMPLETED || status == JobStatus.FAILED)
            System.currentTimeMillis() else null
        jobDao.updateStatus(id, status, err, ts, prog)
    }

    suspend fun updateJobProgress(id: Long, bytes: Long, speedBps: Long) =
        jobDao.updateProgress(id, bytes, speedBps)

    companion object {
        @Volatile private var INSTANCE: SyncRepository? = null
        fun get(ctx: Context) = INSTANCE ?: synchronized(this) {
            INSTANCE ?: SyncRepository(ctx.applicationContext).also { INSTANCE = it }
        }
    }
}
