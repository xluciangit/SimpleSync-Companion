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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val repo  = SyncRepository.get(ctx)
    private val prefs = Prefs.get(ctx)
    private val nm    = ctx.getSystemService(NotificationManager::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(0,  TimeUnit.SECONDS)   // no write timeout for large files
        .readTimeout(60,  TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

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
        setForeground(getForegroundInfo())

        val serverUrl = prefs.serverUrl.first().trimEnd('/')
        val apiKey    = prefs.apiKey.first()
        val directUrl = prefs.directUrl.first().trimEnd('/').ifEmpty { null }

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            nm.notify(NOTIF_ID_DONE, buildErrorNotif("Server URL or API key not configured."))
            return@withContext Result.failure()
        }

        // If uploads are paused, do nothing — leave all jobs as-is
        val paused = prefs.uploadsPaused.first()
        if (paused) {
            return@withContext Result.success()
        }

        // Reset any jobs stuck in UPLOADING from a previous run killed by the OS
        repo.resetStuckUploading()

        if (repo.getPendingJobs().isEmpty()) return@withContext Result.success()

        val total = repo.getPendingJobs().size
        nm.notify(NOTIF_ID_PROGRESS, buildProgressNotif("Starting…", 0, total, 0))

        var succeeded = 0
        var skipped   = 0
        var failed    = 0

        while (true) {
            val pending = repo.getPendingJobs()
            if (pending.isEmpty()) break

            // Re-check paused state at each iteration so pausing takes effect immediately
            if (prefs.uploadsPaused.first()) {
                break
            }

            val job = pending.first()
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

                // Stage file to internal cache so OkHttp knows exact content length
                val inputStream = applicationContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open URI: ${job.relativePath}")

                tmpFile.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()

                // ── Compute SHA-256 hash ──────────────────────────────────────
                val sha256 = computeSha256(tmpFile)

                // ── Ask server if this hash already exists ────────────────────
                val hashExists = checkHashOnServer(serverUrl, apiKey, sha256, job.remoteFolderName)

                if (hashExists) {
                    // File already on server — skip upload, just mark as done
                    repo.updateJobStatus(job.id, JobStatus.COMPLETED, prog = job.fileSize)
                    repo.markUploaded(job, sha256)
                    skipped++
                } else {
                    // ── Upload the file ───────────────────────────────────────
                    val mimeType = applicationContext.contentResolver.getType(uri)
                        ?: "application/octet-stream"

                    var uploadedBytes = 0L
                    var speedStartTime = System.currentTimeMillis()
                    var speedStartBytes = 0L

                    val requestBody = object : RequestBody() {
                        override fun contentType() = mimeType.toMediaType()
                        override fun contentLength() = tmpFile.length()
                        override fun writeTo(sink: okio.BufferedSink) {
                            tmpFile.inputStream().use { inp ->
                                val buf = ByteArray(65_536)
                                var n: Int
                                while (inp.read(buf).also { n = it } != -1) {
                                    sink.write(buf, 0, n)
                                    uploadedBytes += n
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - speedStartTime
                                    // Recalculate speed every ~500ms
                                    if (elapsed >= 500) {
                                        val bytesDelta = uploadedBytes - speedStartBytes
                                        val speedBps = if (elapsed > 0) bytesDelta * 1000 / elapsed else 0L
                                        kotlinx.coroutines.runBlocking {
                                            repo.updateJobProgress(job.id, uploadedBytes, speedBps)
                                        }
                                        speedStartTime = now
                                        speedStartBytes = uploadedBytes
                                    }
                                }
                            }
                        }
                    }

                    // ── Pick upload endpoint based on file size ───────────────
                    // Files over 100 MB bypass the Cloudflare tunnel via direct URL.
                    // check-hash always uses the tunnel (tiny request, no size issue).
                    val CF_LIMIT = 100L * 1024 * 1024
                    val uploadBase = if (tmpFile.length() > CF_LIMIT) {
                        if (directUrl == null) {
                            // No direct URL configured at all — fail permanently
                            repo.updateJobStatus(
                                job.id, JobStatus.FAILED,
                                err = "File is ${tmpFile.length() / 1_048_576} MB — too large for Cloudflare tunnel. Set a Direct Upload URL in server Settings."
                            )
                            failed++
                            continue
                        }
                        // Probe direct URL with a short timeout — if unreachable (e.g. not home),
                        // leave the job PENDING and skip it so smaller files can continue.
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
                            // Direct URL unreachable — leave as PENDING, skip for this run
                            repo.updateJobStatus(job.id, JobStatus.PENDING)
                            continue
                        }
                        directUrl
                    } else {
                        serverUrl
                    }

                    val multipart = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("folder",        job.remoteFolderName)
                        .addFormDataPart("relative_path", job.relativePath)
                        .addFormDataPart("hash",          sha256)
                        .addFormDataPart("android_path",  job.relativePath)
                        .addFormDataPart("file",          job.fileName, requestBody)
                        .build()

                    val request = Request.Builder()
                        .url("$uploadBase/api/upload")
                        .header("x-api-key", apiKey)
                        .post(multipart)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    val isSuccess    = response.isSuccessful
                    val code         = response.code
                    response.close()

                    if (isSuccess) {
                        repo.updateJobStatus(job.id, JobStatus.COMPLETED, prog = job.fileSize)
                        repo.markUploaded(job, sha256)
                        succeeded++
                    } else {
                        repo.updateJobStatus(
                            job.id, JobStatus.FAILED,
                            err = responseBody ?: "HTTP $code"
                        )
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

        nm.cancel(NOTIF_ID_PROGRESS)
        if (succeeded > 0 || skipped > 0 || failed > 0) {
            nm.notify(NOTIF_ID_DONE, buildDoneNotif(succeeded, skipped, failed))
        }

        if (failed > 0) Result.failure() else Result.success()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Compute SHA-256 hex string for the given file. */
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

    /**
     * POST /api/check-hash  { hash, folder }
     * Returns true if the server already has this file → skip upload.
     * On any network error, returns false so the upload proceeds normally.
     */
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
            val text     = response.body?.string()
            response.close()
            if (!response.isSuccessful || text == null) return false
            JSONObject(text).optBoolean("exists", false)
        } catch (_: Exception) {
            false   // network error → let upload proceed
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun buildProgressNotif(text: String, done: Int, total: Int, pct: Int): Notification =
        NotificationCompat.Builder(applicationContext, App.CHANNEL_UPLOAD)
            .setContentTitle("SimpleSync – uploading")
            .setContentText(text)
            .setSubText(if (total > 0) "$done / $total" else null)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildErrorNotif(msg: String): Notification =
        NotificationCompat.Builder(applicationContext, App.CHANNEL_DONE)
            .setContentTitle("SimpleSync – configuration error")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    companion object {
        const val WORK_NAME         = "SimpleSyncCompanion_Upload"
        const val NOTIF_ID_PROGRESS = 1001
        const val NOTIF_ID_DONE     = 1002

        fun enqueue(context: Context) {
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
                ExistingWorkPolicy.KEEP,
                req
            )
        }
    }
}
