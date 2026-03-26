package com.simplesync.companion.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simplesync.companion.data.prefs.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        ScanWorker.runNow(context)

        val prefs = Prefs.get(context)
        runBlocking {
            if (prefs.waEnabled.first()) {
                val hour = prefs.waBackupHour.first()
                WhatsAppBackupWorker.schedule(context, hour)
            }
        }
    }
}
