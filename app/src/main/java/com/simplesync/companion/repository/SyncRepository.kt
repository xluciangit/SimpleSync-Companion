package com.simplesync.companion.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.simplesync.companion.data.db.*
import com.simplesync.companion.data.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class FolderValidation(val inDb: Boolean, val onDisk: Boolean) {
    val isValid: Boolean get() = inDb && onDisk
}

class SyncRepository(private val context: Context) {

    private val db      = AppDatabase.get(context)
    private val cfgDao  = db.folderConfigDao()
    private val trkDao  = db.trackedFileDao()
    private val jobDao  = db.uploadJobDao()
    val prefs = Prefs.get(context)
    val foldersFlow: Flow<List<FolderConfig>> = cfgDao.getAllFlow()

    suspend fun addFolder(cfg: FolderConfig): Long = cfgDao.insert(cfg)
    suspend fun folderExistsByRemoteName(name: String): Boolean = cfgDao.getByRemoteName(name) != null
    suspend fun updateFolder(cfg: FolderConfig) = cfgDao.update(cfg)
    suspend fun deleteFolder(cfg: FolderConfig) {
        cfgDao.delete(cfg)
        trkDao.deleteForConfig(cfg.id)
        jobDao.deleteForConfig(cfg.id)
    }
    val allJobsFlow: Flow<List<UploadJob>> = jobDao.getAllFlow()
    val pendingCountFlow: Flow<Int>        = jobDao.pendingCountFlow()

    suspend fun retryFailed()                     = jobDao.retryAllFailed()
    suspend fun retryJob(id: Long)                = jobDao.retryJob(id)
    suspend fun cancelJob(id: Long)               = jobDao.cancelJob(id)
    suspend fun clearCompleted()                  = jobDao.clearCompleted()
    suspend fun clearSingleCompleted(id: Long)    = jobDao.clearSingleCompleted(id)
    suspend fun clearCancelled()                  = jobDao.clearCancelled()
    suspend fun resetStuckUploading()             = jobDao.resetStuckUploading()
    suspend fun clearJobsForFolder(folderId: Long)= jobDao.deleteForConfig(folderId)
    suspend fun scanAndQueue(config: FolderConfig): Int = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(config.localUri)
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        var newJobs = 0

        suspend fun traverse(doc: DocumentFile, relativeDir: String) {
            for (child in doc.listFiles()) {
                val childName = child.name ?: continue

                if (!config.uploadHiddenFiles && childName.startsWith(".")) continue

                val relPath = if (relativeDir.isEmpty()) childName else "$relativeDir/$childName"

                if (child.isDirectory) { traverse(child, relPath); continue }

                val lastMod  = child.lastModified()
                val size = child.length()
                val existing = trkDao.find(config.id, relPath)

                val isNew = existing == null
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
        jobDao.retryTooBigJobs(config.id)
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
        val ts = if (status == JobStatus.COMPLETED || status == JobStatus.SKIPPED || status == JobStatus.FAILED)
            System.currentTimeMillis() else null
        jobDao.updateStatus(id, status, err, ts, prog)
    }

    suspend fun updateJobProgress(id: Long, bytes: Long, speedBps: Long) =
        jobDao.updateProgress(id, bytes, speedBps)

    suspend fun updateJobUploadNote(id: Long, note: String?) =
        jobDao.updateUploadNote(id, note)

    suspend fun clearAllTrackedFiles() = trkDao.deleteAll()

    suspend fun resetAllLocalData() {
        jobDao.deleteAllJobs()
        trkDao.deleteAll()
        cfgDao.deleteAll()
    }

    suspend fun checkIntegrityFlag(): Boolean = withContext(Dispatchers.IO) {
        try {
            val (serverUrl, apiKey) = getCredentials() ?: return@withContext false
            val client = client
            val request = Request.Builder()
                .url("$serverUrl/api/integrity-status")
                .header("x-api-key", apiKey)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (!response.isSuccessful || body == null) return@withContext false
            JSONObject(body).optInt("flag", 0) == 1
        } catch (_: Exception) { false }
    }

    suspend fun acknowledgeIntegrityFlag() = withContext(Dispatchers.IO) {
        try {
            val (serverUrl, apiKey) = getCredentials() ?: return@withContext
            val client = client
            val request = Request.Builder()
                .url("$serverUrl/api/integrity-acknowledge")
                .header("x-api-key", apiKey)
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    suspend fun validateFolderOnServer(cfg: FolderConfig): FolderValidation = withContext(Dispatchers.IO) {
        try {
            val (serverUrl, apiKey) = getCredentials()
                ?: return@withContext FolderValidation(inDb = true, onDisk = true)
            val encodedName = URLEncoder.encode(cfg.remoteFolderName, "UTF-8")
            val client = client
            val request = Request.Builder()
                .url("$serverUrl/api/folders/validate?name=$encodedName")
                .header("x-api-key", apiKey)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (!response.isSuccessful || body == null) {
                return@withContext FolderValidation(inDb = true, onDisk = true)
            }
            val json = JSONObject(body)
            FolderValidation(
                inDb   = json.optBoolean("inDb",   true),
                onDisk = json.optBoolean("onDisk", true)
            )
        } catch (_: Exception) {
            FolderValidation(inDb = true, onDisk = true)
        }
    }

    private suspend fun getCredentials(): Pair<String, String>? {
        val serverUrl = prefs.serverUrl.first().trimEnd('/')
        val apiKey = prefs.apiKey.first()
        if (serverUrl.isEmpty() || apiKey.isEmpty()) return null
        return serverUrl to apiKey
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }


    companion object {
        @Volatile private var INSTANCE: SyncRepository? = null
        fun get(ctx: Context) = INSTANCE ?: synchronized(this) {
            INSTANCE ?: SyncRepository(ctx.applicationContext).also { INSTANCE = it }
        }
    }
}
