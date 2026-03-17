package com.simplesync.companion.ui.connection

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.simplesync.companion.LoginActivity
import com.simplesync.companion.R
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.databinding.FragmentConnectionBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionFragment : Fragment() {

    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentConnectionBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs.get(requireContext())

        lifecycleScope.launch {
            val url = prefs.serverUrl.first()
            binding.serverUrlDisplay.text = url.ifEmpty { "Not configured" }
        }

        binding.testConnectionBtn.setOnClickListener { testConnection() }
        binding.changeServerBtn.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                putExtra(LoginActivity.EXTRA_RECONFIGURING, true)
            })
        }
    }

    private fun testConnection() {
        val url = binding.serverUrlDisplay.text.toString().trimEnd('/')
        if (url == "Not configured" || url.isEmpty()) {
            Toast.makeText(requireContext(), "No server configured. Tap Change Server.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.testConnectionBtn.isEnabled = false
        binding.testConnectionBtn.text = "Testing…"
        binding.statusText.visibility = View.GONE

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val apiKey = prefs.apiKey.first()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val (ok, msg) = try {
                val ping = client.newCall(okhttp3.Request.Builder().url("$url/api/ping").build()).execute()
                ping.close()
                if (!ping.isSuccessful) throw Exception("Server unreachable (HTTP ${ping.code})")

                val auth = client.newCall(
                    okhttp3.Request.Builder().url("$url/api/folders")
                        .header("x-api-key", apiKey).build()
                ).execute()
                val code = auth.code; auth.close()

                when (code) {
                    200 -> {
                        try {
                            val cfg = client.newCall(
                                okhttp3.Request.Builder().url("$url/api/config")
                                    .header("x-api-key", apiKey).build()
                            ).execute()
                            val body = cfg.body?.string(); cfg.close()
                            if (cfg.isSuccessful && body != null) {
                                val directUrl = JSONObject(body).optString("local_url", "")
                                prefs.setDirectUrl(directUrl)
                            }
                        } catch (_: Exception) {}
                        true to "Connected successfully"
                    }
                    401 -> false to "API key rejected by server"
                    else -> false to "HTTP $code"
                }
            } catch (e: Exception) { false to (e.message ?: "Connection failed") }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                showStatus(msg, !ok)
                binding.testConnectionBtn.isEnabled = true
                binding.testConnectionBtn.text = "Test"
            }
        }
    }

    private fun showStatus(msg: String, isError: Boolean) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = if (isError) "✗  $msg" else "✓  $msg"
        binding.statusText.setBackgroundResource(
            if (isError) R.drawable.status_bg_error else R.drawable.status_bg_success
        )
        binding.statusText.setTextColor(
            if (isError) Color.parseColor("#991B1B") else Color.parseColor("#065F46")
        )
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
