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

    private val db        = AppDatabase.get(context)
    private val cfgDao    = db.folderConfigDao()
    private val trkDao    = db.trackedFileDao()
    private val jobDao    = db.uploadJobDao()
    private val waCacheDao = db.waFileCacheDao()
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
        if (job.folderConfigId == WA_FOLDER_ID) return
        val doc = try {
            DocumentFile.fromSingleUri(context, Uri.parse(job.fileUriString))
        } catch (_: Exception) { null }
        trkDao.upsert(
            TrackedFile(
                folderConfigId = job.folderConfigId,
                relativePath   = job.relativePath,
                lastModified   = doc?.lastModified() ?: 0L,
                fileSize       = doc?.length() ?: job.fileSize,
                sha256Hash     = sha256Hash
            )
        )
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

    data class ServerFolder(val name: String)

    suspend fun getServerFolders(): List<ServerFolder> = withContext(Dispatchers.IO) {
        try {
            val (serverUrl, apiKey) = getCredentials() ?: return@withContext emptyList()
            val response = client.newCall(
                Request.Builder()
                    .url("$serverUrl/api/folders")
                    .header("x-api-key", apiKey)
                    .build()
            ).execute()
            val body = response.body?.string()
            response.close()
            if (!response.isSuccessful || body == null) return@withContext emptyList()
            val arr = org.json.JSONArray(body)
            val list = mutableListOf<ServerFolder>()
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).optString("name", "")
                if (name.isNotEmpty()) list.add(ServerFolder(name))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    data class WhatsAppStatus(
        val hasBackup: Boolean,
        val totalFiles: Int,
        val totalBytes: Long,
        val lastBackup: String?
    )

    data class WhatsAppFileEntry(
        val relativePath: String,
        val hash: String,
        val fileSize: Long
    )

    suspend fun prepareWaBackup() = withContext(Dispatchers.IO) {
        jobDao.resetStuckWaJobs()
    }

    suspend fun insertWaJob(relPath: String, uri: Uri, fileName: String, fileSize: Long): Long =
        withContext(Dispatchers.IO) {
            val existing = jobDao.findWaJobByPath(relPath)
            if (existing != null) {
                // reuse the existing job, reset it to pending so the worker picks it up fresh
                jobDao.resetWaJobForRetry(existing.id, uri.toString())
                existing.id
            } else {
                jobDao.insert(
                    UploadJob(
                        folderConfigId   = WA_FOLDER_ID,
                        remoteFolderName = "WhatsApp",
                        relativePath     = relPath,
                        fileUriString    = uri.toString(),
                        fileName         = fileName,
                        fileSize         = fileSize,
                        status           = JobStatus.PENDING
                    )
                )
            }
        }

    suspend fun getWaCachedHash(relPath: String, lastModified: Long, fileSize: Long): String? =
        withContext(Dispatchers.IO) {
            val cached = waCacheDao.find(relPath) ?: return@withContext null
            if (cached.lastModified == lastModified && cached.fileSize == fileSize) cached.hash else null
        }

    suspend fun saveWaCache(relPath: String, lastModified: Long, fileSize: Long, hash: String) =
        withContext(Dispatchers.IO) {
            waCacheDao.upsert(WaFileCache(relPath, lastModified, fileSize, hash))
        }

    suspend fun getWhatsAppStatus(): WhatsAppStatus? = withContext(Dispatchers.IO) {
        try {
            val (serverUrl, apiKey) = getCredentials() ?: return@withContext null
            val response = client.newCall(
                Request.Builder()
                    .url("$serverUrl/api/whatsapp/status")
                    .header("x-api-key", apiKey)
                    .build()
            ).execute()
            val body = response.body?.string()
            response.close()
            if (!response.isSuccessful || body == null) return@withContext null
            val json = JSONObject(body)
            WhatsAppStatus(
                hasBackup  = json.optBoolean("hasBackup", false),
                totalFiles = json.optInt("totalFiles", 0),
                totalBytes = json.optLong("totalBytes", 0L),
                lastBackup = if (json.isNull("lastBackup")) null else json.optString("lastBackup")
            )
        } catch (_: Exception) { null }
    }

    suspend fun checkWhatsAppFile(relativePath: String, hash: String): Pair<Boolean, Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val (serverUrl, apiKey) = getCredentials() ?: return@withContext Pair(false, false)
                val body = JSONObject()
                    .put("relative_path", relativePath)
                    .put("hash", hash)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val response = client.newCall(
                    Request.Builder()
                        .url("$serverUrl/api/whatsapp/check")
                        .header("x-api-key", apiKey)
                        .post(body)
                        .build()
                ).execute()
                val text = response.body?.string()
                response.close()
                if (!response.isSuccessful || text == null) return@withContext Pair(false, false)
                val json = JSONObject(text)
                Pair(json.optBoolean("exists", false), json.optBoolean("changed", false))
            } catch (_: Exception) { Pair(false, false) }
        }


    suspend fun getWhatsAppFiles(): List<WhatsAppFileEntry> = withContext(Dispatchers.IO) {
        try {
            val (serverUrl, apiKey) = getCredentials() ?: return@withContext emptyList()
            val response = client.newCall(
                Request.Builder()
                    .url("$serverUrl/api/whatsapp/files")
                    .header("x-api-key", apiKey)
                    .build()
            ).execute()
            val body = response.body?.string()
            response.close()
            if (!response.isSuccessful || body == null) return@withContext emptyList()
            val arr = org.json.JSONArray(body)
            val list = mutableListOf<WhatsAppFileEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(WhatsAppFileEntry(
                    relativePath = obj.getString("relative_path"),
                    hash         = obj.getString("hash"),
                    fileSize     = obj.getLong("file_size")
                ))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    suspend fun downloadWhatsAppFile(
        relativePath: String,
        destUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val (serverUrl, apiKey) = getCredentials() ?: return@withContext false
            val encodedPath = java.net.URLEncoder.encode(relativePath, "UTF-8")
            val response = uploadClient.newCall(
                Request.Builder()
                    .url("$serverUrl/api/whatsapp/download?path=$encodedPath")
                    .header("x-api-key", apiKey)
                    .build()
            ).execute()
            if (!response.isSuccessful) { response.close(); return@withContext false }
            val body = response.body
            if (body == null) { response.close(); return@withContext false }
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                body.byteStream().use { it.copyTo(out) }
            } ?: run { response.close(); return@withContext false }
            response.close()
            true
        } catch (_: Exception) { false }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // longer timeouts for WA uploads/downloads
    private val uploadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    companion object {
        const val WA_FOLDER_ID         = -1L
        const val LARGE_FILE_THRESHOLD = 100L * 1024 * 1024

        @Volatile private var INSTANCE: SyncRepository? = null
        fun get(ctx: Context) = INSTANCE ?: synchronized(this) {
            INSTANCE ?: SyncRepository(ctx.applicationContext).also { INSTANCE = it }
        }
    }
}
