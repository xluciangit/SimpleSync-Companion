package com.simplesync.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.simplesync.companion.data.prefs.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        applyTheme()
        createNotificationChannels()
    }

    fun applyTheme() {
        val mode = when (Prefs.get(this).getThemeBlocking()) {
            "light"  -> AppCompatDelegate.MODE_NIGHT_NO
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else     -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val progressChannel = NotificationChannel(
                CHANNEL_UPLOAD,
                "Upload Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active upload progress"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val doneChannel = NotificationChannel(
                CHANNEL_DONE,
                "Upload Results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when uploads complete or fail"
                setShowBadge(true)
            }

            val waProgressChannel = NotificationChannel(
                CHANNEL_WA_UPLOAD,
                "WhatsApp Backup Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows WhatsApp backup and restore progress"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val waDoneChannel = NotificationChannel(
                CHANNEL_WA_DONE,
                "WhatsApp Backup Results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when WhatsApp backup or restore completes"
                setShowBadge(true)
            }

            nm.createNotificationChannel(progressChannel)
            nm.createNotificationChannel(doneChannel)
            nm.createNotificationChannel(waProgressChannel)
            nm.createNotificationChannel(waDoneChannel)
        }
    }

    companion object {
        const val CHANNEL_UPLOAD    = "simplesynccompanion_upload"
        const val CHANNEL_DONE      = "simplesynccompanion_done"
        const val CHANNEL_WA_UPLOAD = "simplesynccompanion_wa_upload"
        const val CHANNEL_WA_DONE   = "simplesynccompanion_wa_done"
    }
}
