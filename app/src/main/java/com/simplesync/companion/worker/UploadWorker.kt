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
import com.simplesync.companion.data.db.UploadJob
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
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
        val apiKey    = prefs.apiKey.first()

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            nm.notify(NOTIF_ID_DONE, buildErrorNotif("Server URL or API key not configured."))
            return@withContext Result.failure()
        }

        try {
            val configClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val configResp = configClient.newCall(
                Request.Builder()
                    .url("$serverUrl/api/config")
                    .header("x-api-key", apiKey)
                    .build()
            ).execute()
            val configBody = configResp.body?.string()
            configResp.close()
            if (configResp.isSuccessful && configBody != null) {
                val json = JSONObject(configBody)
                prefs.setDirectUrl(json.optString("local_url", ""))
                prefs.setUploadMaxBytes(json.optLong("upload_max_bytes", 0L))
            }
        } catch (_: Exception) {}

        val directUrl = prefs.directUrl.first().trimEnd('/').ifEmpty { null }

        if (prefs.uploadsPaused.first()) return@withContext Result.success()

        repo.resetStuckUploading()

        val allPending = repo.getPendingJobs().toMutableList()
        if (allPending.isEmpty()) return@withContext Result.success()

        val total = allPending.size
        nm.notify(NOTIF_ID_PROGRESS, buildProgressNotif("Starting…", 0, total, 0))

        var succeeded = 0
        var skipped   = 0
        var failed    = 0

        val deferredIds          = mutableSetOf<Long>()
        val firstAttemptFailedIds = mutableSetOf<Long>()

        for (job in allPending) {
            if (prefs.uploadsPaused.first()) break
            if (job.id in deferredIds || job.id in firstAttemptFailedIds) continue

            val result = processJob(job, serverUrl, apiKey, directUrl, total, succeeded, skipped, this)

            when (result) {
                is JobResult.Success   -> { succeeded++; if (result.skipped) skipped++ }
                is JobResult.Failed    -> { failed++;    firstAttemptFailedIds.add(job.id) }
                is JobResult.Deferred  -> deferredIds.add(job.id)
                is JobResult.HardFail  -> failed++
            }

            delay(150)
        }

        if (firstAttemptFailedIds.isNotEmpty() && !prefs.uploadsPaused.first()) {
            val retryJobs = repo.getPendingJobs().filter { it.id in firstAttemptFailedIds }
            for (job in retryJobs) {
                if (prefs.uploadsPaused.first()) break
                val result = processJob(job, serverUrl, apiKey, directUrl, total, succeeded, skipped, this)
                when (result) {
                    is JobResult.Success  -> { succeeded++; if (result.skipped) skipped++ }
                    else                  -> { repo.updateJobStatus(job.id, JobStatus.FAILED, err = (result as? JobResult.Failed)?.err ?: "Upload failed"); failed++ }
                }
            }
        }

        nm.cancel(NOTIF_ID_PROGRESS)
        if (succeeded > 0 || skipped > 0 || failed > 0) {
            nm.notify(NOTIF_ID_DONE, buildDoneNotif(succeeded, skipped, failed))
        }

        if (failed > 0) Result.failure() else Result.success()
    }

    private sealed class JobResult {
        data class Success(val skipped: Boolean) : JobResult()
        data class Failed(val err: String)        : JobResult()
        object Deferred                            : JobResult()
        object HardFail                            : JobResult()
    }

    private suspend fun processJob(
        job: UploadJob,
        serverUrl: String,
        apiKey: String,
        directUrl: String?,
        total: Int,
        succeeded: Int,
        skipped: Int,
        scope: kotlinx.coroutines.CoroutineScope
    ): JobResult {
        val uri = Uri.parse(job.fileUriString)

        val maxBytes = prefs.uploadMaxBytes.first()
        if (maxBytes > 0L && job.fileSize > maxBytes) {
            repo.updateJobStatus(job.id, JobStatus.FAILED, err = "File too big, check settings")
            return JobResult.HardFail
        }

        if (job.fileSize > LARGE_FILE_THRESHOLD) {
            if (directUrl == null) {
                repo.updateJobStatus(
                    job.id, JobStatus.FAILED,
                    err = "File is ${job.fileSize / 1_048_576} MB — too large for Cloudflare tunnel. Set a Direct Upload URL in server Settings."
                )
                return JobResult.HardFail
            }
            val reachable = try {
                val ping = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
                    .newCall(Request.Builder().url("$directUrl/api/ping").build())
                    .execute()
                ping.close()
                ping.isSuccessful
            } catch (_: Exception) { false }

            if (!reachable) {
                repo.updateJobStatus(job.id, JobStatus.PENDING)
                repo.updateJobUploadNote(job.id, "Large file – waiting for home network")
                return JobResult.Deferred
            }
        }

        return try {
            repo.updateJobStatus(job.id, JobStatus.HASHING)
            nm.notify(
                NOTIF_ID_PROGRESS,
                buildProgressNotif("Hashing: ${job.fileName}", succeeded + skipped, total,
                    (((succeeded + skipped).toFloat() / total) * 100).toInt())
            )

            val atomicHashBytes = AtomicLong(0L)
            val hashProgressJob = scope.launch {
                while (true) {
                    delay(500)
                    repo.updateJobProgress(job.id, atomicHashBytes.get(), 0L)
                }
            }

            val sha256 = hashFromUri(uri, job.fileSize, atomicHashBytes)
            hashProgressJob.cancel()

            val hashExists = checkHashOnServer(serverUrl, apiKey, sha256, job.remoteFolderName)
            if (hashExists) {
                repo.updateJobStatus(job.id, JobStatus.SKIPPED, prog = job.fileSize)
                repo.markUploaded(job, sha256)
                return JobResult.Success(skipped = true)
            }

            repo.updateJobStatus(job.id, JobStatus.UPLOADING)
            nm.notify(
                NOTIF_ID_PROGRESS,
                buildProgressNotif("Uploading: ${job.fileName}", succeeded + skipped, total,
                    (((succeeded + skipped).toFloat() / total) * 100).toInt())
            )

            val mimeType    = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream"
            val uploadBase  = if (job.fileSize > LARGE_FILE_THRESHOLD) directUrl!! else serverUrl
            repo.updateJobUploadNote(job.id, uploadBase)

            val atomicBytes = AtomicLong(0L)
            val atomicSpeed = AtomicLong(0L)

            val uploadProgressJob = scope.launch {
                while (true) {
                    delay(500)
                    repo.updateJobProgress(job.id, atomicBytes.get(), atomicSpeed.get())
                }
            }

            val requestBody = object : RequestBody() {
                override fun contentType() = mimeType.toMediaType()
                override fun contentLength() = job.fileSize
                override fun writeTo(sink: okio.BufferedSink) {
                    val stream = applicationContext.contentResolver.openInputStream(uri)
                        ?: throw IOException("Cannot open URI: ${job.relativePath}")
                    var uploaded      = 0L
                    var speedStartTime  = System.currentTimeMillis()
                    var speedStartBytes = 0L
                    stream.use { inp ->
                        val buf = ByteArray(65_536)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            sink.write(buf, 0, n)
                            uploaded += n
                            atomicBytes.set(uploaded)
                            val now     = System.currentTimeMillis()
                            val elapsed = now - speedStartTime
                            if (elapsed >= 500) {
                                atomicSpeed.set(if (elapsed > 0) (uploaded - speedStartBytes) * 1000 / elapsed else 0L)
                                speedStartTime  = now
                                speedStartBytes = uploaded
                            }
                        }
                    }
                }
            }

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("folder",        job.remoteFolderName)
                .addFormDataPart("relative_path", job.relativePath)
                .addFormDataPart("hash",          sha256)
                .addFormDataPart("file",          job.fileName, requestBody)
                .build()

            val response     = client.newCall(
                Request.Builder()
                    .url("$uploadBase/api/upload")
                    .header("x-api-key", apiKey)
                    .post(multipart)
                    .build()
            ).execute()
            val responseBody = response.body?.string()
            val isSuccess    = response.isSuccessful
            val code         = response.code
            response.close()
            uploadProgressJob.cancel()

            if (isSuccess) {
                val serverSkipped = try {
                    responseBody != null && JSONObject(responseBody).optBoolean("skipped", false)
                } catch (_: Exception) { false }

                if (serverSkipped) {
                    repo.updateJobStatus(job.id, JobStatus.SKIPPED, prog = job.fileSize)
                    repo.updateJobUploadNote(job.id, uploadBase)
                    repo.markUploaded(job, sha256)
                    JobResult.Success(skipped = true)
                } else {
                    repo.updateJobStatus(job.id, JobStatus.COMPLETED, prog = job.fileSize)
                    repo.updateJobUploadNote(job.id, uploadBase)
                    repo.markUploaded(job, sha256)
                    JobResult.Success(skipped = false)
                }
            } else {
                val errMsg = when (code) {
                    507  -> "Server storage is full — upload rejected."
                    else -> responseBody ?: "HTTP $code"
                }
                repo.updateJobStatus(job.id, JobStatus.FAILED, err = errMsg)
                JobResult.Failed(errMsg)
            }

        } catch (e: Exception) {
            repo.updateJobStatus(job.id, JobStatus.PENDING, err = e.message ?: "Upload failed")
            JobResult.Failed(e.message ?: "Upload failed")
        }
    }

    private fun hashFromUri(uri: Uri, fileSize: Long, progress: AtomicLong): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream: InputStream = applicationContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open URI for hashing")
        var read = 0L
        stream.use { inp ->
            val buf = ByteArray(65_536)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
                read += n
                progress.set(read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkHashOnServer(serverUrl: String, apiKey: String, hash: String, folder: String): Boolean {
        return try {
            val body = JSONObject().put("hash", hash).put("folder", folder)
                .toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(
                Request.Builder()
                    .url("$serverUrl/api/check-hash")
                    .header("x-api-key", apiKey)
                    .post(body)
                    .build()
            ).execute()
            val text = response.body?.string()
            response.close()
            if (!response.isSuccessful || text == null) return false
            JSONObject(text).optBoolean("exists", false)
        } catch (_: Exception) { false }
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
        const val WORK_NAME            = "SimpleSyncCompanion_Upload"
        const val NOTIF_ID_PROGRESS    = 1001
        const val NOTIF_ID_DONE        = 1002

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

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                req
            )
        }
    }
}
