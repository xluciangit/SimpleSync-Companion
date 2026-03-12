package com.simplesync.companion.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.simplesync.companion.App
import com.simplesync.companion.MainActivity
import com.simplesync.companion.repository.SyncRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val openIntent = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, App.CHANNEL_UPLOAD)
            .setContentTitle("SimpleSync Companion")
            .setContentText("Scanning for new files…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
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
        setForeground(getForegroundInfo())

        val repo    = SyncRepository.get(applicationContext)
        val nm      = applicationContext.getSystemService(NotificationManager::class.java)
        val configs = repo.foldersFlow.first().filter { it.isActive }

        var totalQueued = 0
        for (cfg in configs) {
            try {
                totalQueued += repo.scanAndQueue(cfg)
            } catch (_: Exception) {}
        }

        nm.cancel(NOTIF_ID_SCAN)

        if (totalQueued > 0) {
            UploadWorker.enqueue(applicationContext)
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME     = "SimpleSyncCompanion_Scan"
        const val NOTIF_ID_SCAN = 1003

        /**
         * Schedule periodic scanning.
         *
         * Bug 2 fix: PeriodicWorkRequest does NOT support setExpedited() —
         * WorkManager throws IllegalArgumentException at enqueue time.
         * Removed setExpedited() from the periodic request entirely.
         *
         * Bug 5 fix: Scanning is local file I/O — no network needed.
         * Network constraint removed from scan; only UploadWorker needs it.
         */
        fun schedule(context: Context, intervalMinutes: Int) {
            val req = PeriodicWorkRequestBuilder<ScanWorker>(
                intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES
            ).build()   // no network constraint, no setExpedited

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

        /**
         * One-shot immediate scan.
         * setExpedited() IS valid on OneTimeWorkRequest — kept here.
         * Network constraint removed: scan reads local files only.
         */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<ScanWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()   // no network constraint for scan

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_now",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
