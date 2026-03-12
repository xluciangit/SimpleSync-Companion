package com.simplesync.companion

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.databinding.ActivityMainBinding
import com.simplesync.companion.worker.ScanWorker
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* silently handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if the app is configured (server URL + API key saved).
        // runBlocking is intentional here — this is a one-time startup check
        // and DataStore returns its in-memory cache immediately after the first read.
        val configured = runBlocking { Prefs.get(this@MainActivity).isConfigured() }
        if (!configured) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)

        // Ensure periodic scanning is scheduled on every launch.
        // 60 min default; FoldersViewModel will call schedule() again with the
        // correct per-folder interval when a folder is updated or added.
        ScanWorker.schedule(this, 60)

        requestNotificationPermission()
        requestBatteryOptimisationExemption()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBatteryOptimisationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle("Allow background uploads")
            .setMessage(
                "SimpleSync Companion needs to run in the background to upload your files automatically.\n\n" +
                "On the next screen, select \"Allow\" so the app is not stopped by battery optimisation.\n\n" +
                "Without this, uploads will only run while the app is open."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
            .setNegativeButton("Not now", null)
            .setCancelable(false)
            .show()
    }
}
