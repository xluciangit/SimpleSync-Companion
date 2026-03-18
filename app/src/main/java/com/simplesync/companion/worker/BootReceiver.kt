package com.simplesync.companion.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Service
import android.os.IBinder

class UploadForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ScanWorker.runNow(context)
        }
    }
}
