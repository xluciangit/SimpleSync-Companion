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
import com.simplesync.companion.R

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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF3F86E8.toInt())
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

        val repo = SyncRepository.get(applicationContext)
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val configs = repo.foldersFlow.first().filter { it.isActive }

        var totalQueued = 0

        for (cfg in configs) {
            try {
                if (cfg.lastScanAt > 0) {
                    val validation = repo.validateFolderOnServer(cfg)
                    if (!validation.isValid) {
                        repo.deleteFolder(cfg)
                        continue
                    }
                }
                totalQueued += repo.scanAndQueue(cfg)

            } catch (_: Exception) {}
        }

        nm.cancel(NOTIF_ID_SCAN)

        if (totalQueued > 0) {
            UploadWorker.enqueue(applicationContext, replace = true)
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME     = "SimpleSyncCompanion_Scan"
        const val NOTIF_ID_SCAN = 1003

        fun schedule(context: Context, intervalMinutes: Int) {
            val req = PeriodicWorkRequestBuilder<ScanWorker>(
                intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<ScanWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_now",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
