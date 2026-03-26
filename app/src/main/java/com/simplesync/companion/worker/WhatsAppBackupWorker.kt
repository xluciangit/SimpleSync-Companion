package com.simplesync.companion.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.simplesync.companion.App
import com.simplesync.companion.MainActivity
import com.simplesync.companion.R
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.repository.SyncRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.util.Calendar
import java.util.concurrent.TimeUnit

class WhatsAppBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val prefs = Prefs.get(ctx)
    private val repo  = SyncRepository.get(ctx)
    private val nm    = ctx.getSystemService(NotificationManager::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val openIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, App.CHANNEL_UPLOAD)
            .setContentTitle("SimpleSync – WhatsApp")
            .setContentText("Scanning backup folder…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF25D366.toInt())
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(
            NOTIF_ID_SCAN,
            notif,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result {
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}

        if (prefs.uploadsPaused.first()) return Result.success()

        val waUriStr = prefs.waUri.first()
        if (waUriStr.isEmpty()) return Result.failure()

        val treeUri = android.net.Uri.parse(waUriStr)
        val treeDoc = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: return Result.failure()

        repo.prepareWaBackup()

        var queued = 0

        try {
            suspend fun walk(doc: DocumentFile, relDir: String) {
                for (child in doc.listFiles()) {
                    if (!coroutineContext.isActive || prefs.uploadsPaused.first()) return

                    val name = child.name ?: continue
                    val relPath = if (relDir.isEmpty()) name else "$relDir/$name"
                    if (child.isDirectory) { walk(child, relPath); continue }

                    val cachedHash = repo.getWaCachedHash(relPath, child.lastModified(), child.length())
                    if (cachedHash != null) continue

                    val jobId = repo.insertWaJob(relPath, child.uri, name, child.length())
                    if (jobId > 0L) queued++
                }
            }

            walk(treeDoc, "")
        } catch (_: Exception) {
            nm.cancel(NOTIF_ID_SCAN)
            return Result.retry()
        }

        nm.cancel(NOTIF_ID_SCAN)

        prefs.setWaLastScanAt(System.currentTimeMillis())

        if (queued > 0 && !prefs.uploadsPaused.first()) {
            UploadWorker.enqueue(applicationContext, replace = true)
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME     = "SimpleSyncCompanion_WABackup"
        const val NOTIF_ID_SCAN = 2001

        fun schedule(context: Context, hour: Int) {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val delayMs = next.timeInMillis - now.timeInMillis

            val req = OneTimeWorkRequestBuilder<WhatsAppBackupWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<WhatsAppBackupWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_now",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork("${WORK_NAME}_now")
        }
    }
}
