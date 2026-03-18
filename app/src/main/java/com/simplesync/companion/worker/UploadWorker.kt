package com.simplesync.companion.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.simplesync.companion.App
import com.simplesync.companion.MainActivity
import com.simplesync.companion.data.db.JobStatus
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.simplesync.companion.R

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val repo  = SyncRepository.get(ctx)
    private val prefs = Prefs.get(ctx)
    private val nm    = ctx.getSystemService(NotificationManager::class.java)

    private val client get() = httpClient

    private val openAppIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            NOTIF_ID_PROGRESS,
            buildProgressNotif("Preparing uploads…", 0, 0, 0),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}

        val serverUrl = prefs.serverUrl.first().trimEnd('/')
        val apiKey = prefs.apiKey.first()

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            nm.notify(NOTIF_ID_DONE, buildErrorNotif("Server URL or API key not configured."))
            return@withContext Result.failure()
        }

        try {
            val configClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val configReq = Request.Builder()
                .url("$serverUrl/api/config")
                .header("x-api-key", apiKey)
                .build()
            val configResp = configClient.newCall(configReq).execute()
            val configBody = configResp.body?.string()
            configResp.close()
            if (configResp.isSuccessful && configBody != null) {
                val json = JSONObject(configBody)
                prefs.setDirectUrl(json.optString("local_url", ""))
                val maxBytes = json.optLong("upload_max_bytes", 0L)
                prefs.setUploadMaxBytes(maxBytes)
            }
        } catch (_: Exception) {}

        val directUrl = prefs.directUrl.first().trimEnd('/').ifEmpty { null }

        val paused = prefs.uploadsPaused.first()
        if (paused) return@withContext Result.success()

        repo.resetStuckUploading()

        if (repo.getPendingJobs().isEmpty()) return@withContext Result.success()

        val total = repo.getPendingJobs().size
        nm.notify(NOTIF_ID_PROGRESS, buildProgressNotif("Starting…", 0, total, 0))

        var succeeded = 0
        var skipped = 0
        var failed = 0

        val deferredIds = mutableSetOf<Long>()
        val firstAttemptFailedIds = mutableSetOf<Long>()

        while (true) {
            val pending = repo.getPendingJobs()
            if (pending.isEmpty()) break

            if (prefs.uploadsPaused.first()) break

            val job = pending.firstOrNull { it.id !in deferredIds && it.id !in firstAttemptFailedIds }
            if (job == null) break
            repo.updateJobStatus(job.id, JobStatus.UPLOADING)

            nm.notify(
                NOTIF_ID_PROGRESS,
                buildProgressNotif(
                    "Uploading: ${job.fileName}",
                    succeeded + skipped,
                    total,
                    (((succeeded + skipped).toFloat() / total) * 100).toInt()
                )
            )

            val tmpFile = File(applicationContext.cacheDir, "ssc_upload_${job.id}")
            try {
                val uri = Uri.parse(job.fileUriString)

                val maxBytes = prefs.uploadMaxBytes.first()
                if (maxBytes > 0L && job.fileSize > maxBytes) {
                    repo.updateJobStatus(job.id, JobStatus.FAILED, err = "File too big, get Admin to increase file size limit.")
                    failed++
                    continue
                }

                if (job.fileSize > LARGE_FILE_THRESHOLD) {
                    if (directUrl == null) {
                        repo.updateJobStatus(
                            job.id, JobStatus.FAILED,
                            err = "File is ${job.fileSize / 1_048_576} MB — too large for Cloudflare tunnel. Set a Direct Upload URL in server Settings."
                        )
                        failed++
                        continue
                    }
                    val reachable = try {
                        val pingClient = OkHttpClient.Builder()
                            .connectTimeout(3, TimeUnit.SECONDS)
                            .readTimeout(3, TimeUnit.SECONDS)
                            .build()
                        val ping = pingClient.newCall(
                            Request.Builder().url("$directUrl/api/ping").build()
                        ).execute()
                        ping.close()
                        ping.isSuccessful
                    } catch (_: Exception) { false }

                    if (!reachable) {
                        repo.updateJobStatus(job.id, JobStatus.PENDING)
                        repo.updateJobUploadNote(job.id, "Large file – waiting for home network")
                        deferredIds.add(job.id)
                        continue
                    }
                }

                val inputStream = applicationContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open URI: ${job.relativePath}")
                tmpFile.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()

                val sha256 = computeSha256(tmpFile)
                val hashExists = checkHashOnServer(serverUrl, apiKey, sha256, job.remoteFolderName)

                if (hashExists) {
                    repo.updateJobStatus(job.id, JobStatus.SKIPPED, prog = job.fileSize)
                    repo.markUploaded(job, sha256)
                    skipped++
                } else {
                    val mimeType = applicationContext.contentResolver.getType(uri)
                        ?: "application/octet-stream"

                    val atomicBytes = AtomicLong(0L)
                    val atomicSpeed = AtomicLong(0L)

                    val progressJob = launch {
                        while (true) {
                            delay(500)
                            repo.updateJobProgress(job.id, atomicBytes.get(), atomicSpeed.get())
                        }
                    }

                    val requestBody = object : RequestBody() {
                        override fun contentType() = mimeType.toMediaType()
                        override fun contentLength() = tmpFile.length()
                        override fun writeTo(sink: okio.BufferedSink) {
                            var uploadedBytes = 0L
                            var speedStartTime  = System.currentTimeMillis()
                            var speedStartBytes = 0L
                            tmpFile.inputStream().use { inp ->
                                val buf = ByteArray(65_536)
                                var n: Int
                                while (inp.read(buf).also { n = it } != -1) {
                                    sink.write(buf, 0, n)
                                    uploadedBytes += n
                                    atomicBytes.set(uploadedBytes)
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - speedStartTime
                                    if (elapsed >= 500) {
                                        val bps = if (elapsed > 0)
                                            (uploadedBytes - speedStartBytes) * 1000 / elapsed else 0L
                                        atomicSpeed.set(bps)
                                        speedStartTime  = now
                                        speedStartBytes = uploadedBytes
                                    }
                                }
                            }
                        }
                    }

                    val uploadBase = if (tmpFile.length() > LARGE_FILE_THRESHOLD) directUrl!! else serverUrl

                    repo.updateJobUploadNote(job.id, uploadBase)

                    val multipart = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("folder",        job.remoteFolderName)
                        .addFormDataPart("relative_path", job.relativePath)
                        .addFormDataPart("hash",          sha256)
                        .addFormDataPart("file",          job.fileName, requestBody)
                        .build()

                    val request = Request.Builder()
                        .url("$uploadBase/api/upload")
                        .header("x-api-key", apiKey)
                        .post(multipart)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    val isSuccess = response.isSuccessful
                    val code = response.code
                    response.close()

                    progressJob.cancel()

                    if (isSuccess) {
                        val serverSkipped = try {
                            responseBody != null && JSONObject(responseBody).optBoolean("skipped", false)
                        } catch (_: Exception) { false }

                        if (serverSkipped) {
                            repo.updateJobStatus(job.id, JobStatus.SKIPPED, prog = job.fileSize)
                            repo.updateJobUploadNote(job.id, uploadBase)
                            skipped++
                        } else {
                            repo.updateJobStatus(job.id, JobStatus.COMPLETED, prog = job.fileSize)
                            repo.updateJobUploadNote(job.id, uploadBase)
                            succeeded++
                        }
                        repo.markUploaded(job, sha256)
                    } else {
                        val errMsg = when (code) {
                            507  -> "Server storage is full — upload rejected."
                            else -> responseBody ?: "HTTP $code"
                        }
                        repo.updateJobStatus(job.id, JobStatus.FAILED, err = errMsg)
                        failed++
                    }
                }

            } catch (e: Exception) {
                if (job.id !in firstAttemptFailedIds) {
                    repo.updateJobStatus(job.id, JobStatus.PENDING, err = e.message ?: "Upload failed")
                    firstAttemptFailedIds.add(job.id)
                } else {
                    repo.updateJobStatus(job.id, JobStatus.FAILED, err = e.message ?: "Unknown error")
                    failed++
                }
            } finally {
                try { tmpFile.delete() } catch (_: Exception) {}
            }
            delay(150)
        }

        if (firstAttemptFailedIds.isNotEmpty() && !prefs.uploadsPaused.first()) {
            for (retryId in firstAttemptFailedIds) {
                if (prefs.uploadsPaused.first()) break
                val job = repo.getPendingJobs().firstOrNull { it.id == retryId } ?: continue
                repo.updateJobStatus(job.id, JobStatus.UPLOADING)
                val tmpFile = File(applicationContext.cacheDir, "ssc_retry_${job.id}")
                try {
                    val uri = Uri.parse(job.fileUriString)
                    val inputStream = applicationContext.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Cannot open URI")
                    tmpFile.outputStream().use { out -> inputStream.copyTo(out) }
                    inputStream.close()
                    val sha256 = computeSha256(tmpFile)
                    val hashExists = checkHashOnServer(serverUrl, apiKey, sha256, job.remoteFolderName)
                    if (hashExists) {
                        repo.updateJobStatus(job.id, JobStatus.SKIPPED, prog = job.fileSize)
                        repo.markUploaded(job, sha256)
                        skipped++
                    } else {
                        val mimeType = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream"
                        val uploadBase = if (tmpFile.length() > 100L * 1024 * 1024) directUrl ?: serverUrl else serverUrl
                        val requestBody = tmpFile.asRequestBody(mimeType.toMediaType())
                        val multipart = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("folder", job.remoteFolderName)
                            .addFormDataPart("relative_path", job.relativePath)
                            .addFormDataPart("hash", sha256)
                            .addFormDataPart("file", job.fileName, requestBody)
                            .build()
                        val request = Request.Builder()
                            .url("$uploadBase/api/upload")
                            .header("x-api-key", apiKey)
                            .post(multipart)
                            .build()
                        val response = client.newCall(request).execute()
                        val responseBody = response.body?.string()
                        val isSuccess = response.isSuccessful
                        val code = response.code
                        response.close()
                        if (isSuccess) {
                            val serverSkipped = try { responseBody != null && JSONObject(responseBody).optBoolean("skipped", false) } catch (_: Exception) { false }
                            if (serverSkipped) {
                                repo.updateJobStatus(job.id, JobStatus.SKIPPED, prog = job.fileSize)
                                skipped++
                            } else {
                                repo.updateJobStatus(job.id, JobStatus.COMPLETED, prog = job.fileSize)
                                succeeded++
                            }
                            repo.markUploaded(job, sha256)
                        } else {
                            val errMsg = if (code == 507) "Server storage is full — upload rejected." else responseBody ?: "HTTP $code"
                            repo.updateJobStatus(job.id, JobStatus.FAILED, err = errMsg)
                            failed++
                        }
                    }
                } catch (e: Exception) {
                    repo.updateJobStatus(job.id, JobStatus.FAILED, err = e.message ?: "Unknown error")
                    failed++
                } finally {
                    try { tmpFile.delete() } catch (_: Exception) {}
                }
            }
        }

        nm.cancel(NOTIF_ID_PROGRESS)
        if (succeeded > 0 || skipped > 0 || failed > 0) {
            nm.notify(NOTIF_ID_DONE, buildDoneNotif(succeeded, skipped, failed))
        }

        if (failed > 0) Result.failure() else Result.success()
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(65_536)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkHashOnServer(
        serverUrl: String,
        apiKey: String,
        hash: String,
        folder: String
    ): Boolean {
        return try {
            val body = JSONObject()
                .put("hash",   hash)
                .put("folder", folder)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$serverUrl/api/check-hash")
                .header("x-api-key", apiKey)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val text = response.body?.string()
            response.close()
            if (!response.isSuccessful || text == null) return false
            JSONObject(text).optBoolean("exists", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun buildProgressNotif(text: String, done: Int, total: Int, pct: Int): Notification =
        NotificationCompat.Builder(applicationContext, App.CHANNEL_UPLOAD)
            .setContentTitle("SimpleSync – uploading")
            .setContentText(text)
            .setSubText(if (total > 0) "$done / $total" else null)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF3F86E8.toInt())
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun buildDoneNotif(done: Int, skipped: Int, failed: Int): Notification {
        val text = buildString {
            if (done > 0)    append("✓ $done uploaded")
            if (skipped > 0) { if (done > 0) append("  "); append("⏭ $skipped already on server") }
            if (failed > 0)  { if (done + skipped > 0) append("  "); append("✗ $failed failed") }
        }
        return NotificationCompat.Builder(applicationContext, App.CHANNEL_DONE)
            .setContentTitle("SimpleSync Companion")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF3F86E8.toInt())
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildErrorNotif(msg: String): Notification =
        NotificationCompat.Builder(applicationContext, App.CHANNEL_DONE)
            .setContentTitle("SimpleSync – configuration error")
            .setContentText(msg)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF3F86E8.toInt())
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.MINUTES)
                .readTimeout(300, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        const val LARGE_FILE_THRESHOLD = 100L * 1024 * 1024
        const val WORK_NAME         = "SimpleSyncCompanion_Upload"
        const val NOTIF_ID_PROGRESS = 1001
        const val NOTIF_ID_DONE     = 1002

        fun cancelAll(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

        fun enqueue(context: Context, replace: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, req)
        }
    }
}
