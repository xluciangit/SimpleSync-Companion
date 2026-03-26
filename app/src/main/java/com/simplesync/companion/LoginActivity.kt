package com.simplesync.companion

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.repository.SyncRepository
import com.simplesync.companion.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val prefs by lazy { Prefs.get(this) }

    private var isReconfiguring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isReconfiguring = intent.getBooleanExtra(EXTRA_RECONFIGURING, false)

        if (isReconfiguring) {
            lifecycleScope.launch {
                binding.serverUrlEt.setText(prefs.serverUrl.first())
                binding.apiKeyEt.setText(prefs.apiKey.first())
            }
            binding.cancelBtn.visibility = android.view.View.VISIBLE
            binding.cancelBtn.setOnClickListener { finish() }
        }

        binding.testConnectionBtn.setOnClickListener { testConnection() }
        binding.connectBtn.setOnClickListener { saveAndConnect() }
    }

    private fun testConnection() {
        val url = binding.serverUrlEt.text.toString().trim().trimEnd('/')
        val key = binding.apiKeyEt.text.toString().trim()

        if (url.isEmpty()) {
            showStatus("Enter a server URL first", isError = true)
            return
        }

        setUiEnabled(false)
        binding.testConnectionBtn.text = "Testing…"

        lifecycleScope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val (ok, msg) = try {
                val ping = client.newCall(Request.Builder().url("$url/api/ping").build()).execute()
                ping.close()
                if (!ping.isSuccessful) throw Exception("Server unreachable (HTTP ${ping.code})")

                if (key.isEmpty()) {
                    true to "Server reachable (no API key entered yet)"
                } else {
                    val auth = client.newCall(
                        Request.Builder().url("$url/api/folders")
                            .header("x-api-key", key).build()
                    ).execute()
                    val code = auth.code
                    auth.close()
                    when (code) {
                        200  -> true  to "Connected successfully"
                        401  -> false to "API key rejected by server"
                        else -> false to "Unexpected response: HTTP $code"
                    }
                }
            } catch (e: Exception) {
                false to "${e.message ?: "Connection failed"}"
            }

            withContext(Dispatchers.Main) {
                showStatus(msg, isError = !ok)
                setUiEnabled(true)
                binding.testConnectionBtn.text = "Test Connection"
            }
        }
    }

    private fun saveAndConnect() {
        val url = binding.serverUrlEt.text.toString().trim().trimEnd('/')
        val key = binding.apiKeyEt.text.toString().trim()

        if (url.isEmpty() || key.isEmpty()) {
            showStatus("Server URL and API key are required", isError = true)
            return
        }

        if (isReconfiguring) {
            lifecycleScope.launch {
                val oldUrl = prefs.serverUrl.first()
                val oldKey = prefs.apiKey.first()
                if (url != oldUrl || key != oldKey) {
                    withContext(Dispatchers.Main) {
                        val dlg = MaterialAlertDialogBuilder(this@LoginActivity)
                            .setTitle("Change connection?")
                            .setMessage("Changing your server or API key will remove all configured folders and clear the upload queue.")
                            .setPositiveButton("Continue") { _, _ ->
                                lifecycleScope.launch { doSave(url, key, clearData = true) }
                            }
                            .setNegativeButton("Cancel", null)
                            .create()
                        dlg.show()
                        dlg.window?.setBackgroundDrawable(dialogBackground(this@LoginActivity))
                    }
                } else {
                    doSave(url, key, clearData = false)
                }
            }
        } else {
            lifecycleScope.launch { doSave(url, key, clearData = false) }
        }
    }

    private suspend fun doSave(url: String, key: String, clearData: Boolean) {
        if (clearData) {
            SyncRepository.get(this).resetAllLocalData()
            com.simplesync.companion.worker.ScanWorker.cancel(this)
            com.simplesync.companion.worker.UploadWorker.cancelAll(this)
        }

        prefs.setServerUrl(url)
        prefs.setApiKey(key)

        withContext(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val resp = client.newCall(
                    Request.Builder().url("$url/api/config")
                        .header("x-api-key", key).build()
                ).execute()
                val body = resp.body?.string()
                resp.close()
                if (resp.isSuccessful && body != null) {
                    val directUrl = org.json.JSONObject(body).optString("local_url", "")
                    prefs.setDirectUrl(directUrl)
                }
            } catch (_: Exception) {}
        }

        withContext(Dispatchers.Main) {
            if (isReconfiguring) {
                Toast.makeText(this@LoginActivity, "Connection settings saved", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun showStatus(msg: String, isError: Boolean) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = if (isError) "✗  $msg" else "✓  $msg"
        binding.statusText.setTextColor(
            if (isError) Color.parseColor("#991B1B") else Color.parseColor("#065F46")
        )
        binding.statusText.setBackgroundResource(
            if (isError) R.drawable.status_bg_error else R.drawable.status_bg_success
        )
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.serverUrlEt.isEnabled    = enabled
        binding.apiKeyEt.isEnabled       = enabled
        binding.testConnectionBtn.isEnabled = enabled
        binding.connectBtn.isEnabled     = enabled
    }

    companion object {
        const val EXTRA_RECONFIGURING = "is_reconfiguring"
    }

private fun dialogBackground(ctx: android.content.Context): android.graphics.drawable.GradientDrawable {
    val ta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
    val color = ta.getColor(0, android.graphics.Color.WHITE)
    ta.recycle()
    return android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = ctx.resources.getDimension(R.dimen.dialog_corner_radius)
    }
}

}
