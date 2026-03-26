package com.simplesync.companion.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.simplesync.companion.App
import com.simplesync.companion.MainActivity
import com.simplesync.companion.R
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.repository.SyncRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

class WhatsAppRestoreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val prefs = Prefs.get(ctx)
    private val repo  = SyncRepository.get(ctx)
    private val nm    = ctx.getSystemService(NotificationManager::class.java)

    private val openIntent by lazy {
        PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notif = NotificationCompat.Builder(applicationContext, App.CHANNEL_WA_UPLOAD)
            .setContentTitle("SimpleSync – WhatsApp restore")
            .setContentText("Starting restore…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF25D366.toInt())
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(
            NOTIF_ID,
            notif,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result = UploadLock.mutex.withLock {
        doWorkLocked()
    }

    private suspend fun doWorkLocked(): Result {
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}

        val waUriStr = prefs.waUri.first()
        if (waUriStr.isEmpty()) return Result.failure()

        val treeDoc = DocumentFile.fromTreeUri(applicationContext, Uri.parse(waUriStr))
            ?: return Result.failure()

        val files = repo.getWhatsAppFiles()
        if (files.isEmpty()) {
            nm.cancel(NOTIF_ID)
            return Result.success()
        }

        val total   = files.size
        var success = 0
        var skipped = 0
        var failed  = 0

        files.forEachIndexed { index, entry ->
            notify("Restoring ${entry.relativePath}", index, total)

            try {
                val existingUri = findExistingFile(treeDoc, entry.relativePath)
                if (existingUri != null && hashUri(existingUri) == entry.hash) {
                    skipped++
                    return@forEachIndexed
                }

                val destUri = resolveOrCreateFile(treeDoc, entry.relativePath)
                if (destUri == null) { failed++; return@forEachIndexed }

                if (repo.downloadWhatsAppFile(entry.relativePath, destUri)) success++ else failed++
            } catch (_: Exception) {
                failed++
            }
        }

        nm.cancel(NOTIF_ID)

        val parts = mutableListOf<String>()
        if (success > 0) parts.add("✓ $success restored")
        if (skipped > 0) parts.add("⏭ $skipped already present")
        if (failed  > 0) parts.add("✗ $failed failed")
        val text = parts.joinToString("  ")

        nm.notify(
            NOTIF_ID_DONE,
            NotificationCompat.Builder(applicationContext, App.CHANNEL_WA_DONE)
                .setContentTitle("WhatsApp restore complete")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setColor(0xFF25D366.toInt())
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )

        return if (failed > 0) Result.retry() else Result.success()
    }

    private fun findExistingFile(root: DocumentFile, relativePath: String): Uri? {
        val parts = relativePath.split("/")
        var current = root
        for (i in 0 until parts.size - 1) {
            current = current.findFile(parts[i]) ?: return null
        }
        return current.findFile(parts.last())?.uri
    }

    private fun hashUri(uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(8192)
                var n: Int
                while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { null }
    }

    private fun resolveOrCreateFile(root: DocumentFile, relativePath: String): Uri? {
        val parts = relativePath.split("/")
        var current = root

        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            current = current.findFile(dirName)
                ?: current.createDirectory(dirName)
                ?: return null
        }

        val fileName = parts.last()
        current.findFile(fileName)?.delete()

        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        return current.createFile(mimeType, fileName)?.uri
    }

    private fun notify(text: String, done: Int, total: Int) {
        val pct = if (total > 0) (done * 100 / total) else 0
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(applicationContext, App.CHANNEL_WA_UPLOAD)
                .setContentTitle("SimpleSync – WhatsApp restore")
                .setContentText(text)
                .setSubText("$done / $total")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setColor(0xFF25D366.toInt())
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, total == 0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    companion object {
        const val WORK_NAME     = "SimpleSyncCompanion_WARestore"
        const val NOTIF_ID      = 2003
        const val NOTIF_ID_DONE = 2004

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<WhatsAppRestoreWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
