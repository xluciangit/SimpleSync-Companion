package com.simplesync.companion.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import com.simplesync.companion.App
import com.simplesync.companion.LoginActivity
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.databinding.FragmentSettingsBinding
import com.simplesync.companion.worker.ScanWorker
import com.simplesync.companion.worker.UploadWorker
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// ── ViewModel ─────────────────────────────────────────────────────────────────
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs.get(app)
    val serverUrl: LiveData<String>  = prefs.serverUrl.asLiveData()
    val apiKey:    LiveData<String>  = prefs.apiKey.asLiveData()
    val notifsOn:  LiveData<Boolean> = prefs.notifsOn.asLiveData()
    val appTheme:  LiveData<String>  = prefs.appTheme.asLiveData()
    fun setNotifs(on: Boolean) = viewModelScope.launch { prefs.setNotifsOn(on) }
    fun setTheme(t: String)    = viewModelScope.launch { prefs.setAppTheme(t) }
}

// ── Fragment ──────────────────────────────────────────────────────────────────
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: SettingsViewModel by viewModels()
    private val prefs by lazy { Prefs.get(requireContext()) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm.serverUrl.observe(viewLifecycleOwner) { url ->
            binding.serverUrlDisplay.text = if (url.isNotEmpty()) url else "Not configured"
        }
        // Observe apiKey to ensure LiveData is active and .value is available for testConnection()
        vm.apiKey.observe(viewLifecycleOwner) { /* activates the LiveData */ }

        vm.notifsOn.observe(viewLifecycleOwner) { binding.notifsSwitch.isChecked = it }
        binding.notifsSwitch.setOnCheckedChangeListener { _, checked -> vm.setNotifs(checked) }

        // ── Theme picker ──────────────────────────────────────────────────
        vm.appTheme.observe(viewLifecycleOwner) { updateThemeButtons(it) }
        binding.themeDarkBtn.setOnClickListener   { applyTheme("dark") }
        binding.themeSystemBtn.setOnClickListener { applyTheme("system") }
        binding.themeLightBtn.setOnClickListener  { applyTheme("light") }

        binding.testConnectionBtn.setOnClickListener { testConnection() }

        binding.changeServerBtn.setOnClickListener {
            startActivity(
                Intent(requireContext(), LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_RECONFIGURING, true)
            )
        }

        binding.syncNowBtn.setOnClickListener {
            ScanWorker.runNow(requireContext())
            Toast.makeText(requireContext(), "Scan started…", Toast.LENGTH_SHORT).show()
        }

        }

        binding.batteryBtn.setOnClickListener { openBatterySettings() }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryStatus()
    }

    private fun updateBatteryStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.batteryStatusText.text = "Not required on this Android version"
            binding.batteryBtn.isEnabled = false
            return
        }
        val pm     = requireContext().getSystemService(PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(requireContext().packageName)
        binding.batteryStatusText.text = if (exempt)
            "✓ Battery optimisation is disabled – background uploads will work reliably"
        else
            "⚠ Battery optimisation is ON – background uploads may be interrupted"
        binding.batteryBtn.text = if (exempt) "Battery Settings" else "Disable Battery Optimisation"
    }

    private fun openBatterySettings() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm     = ctx.getSystemService(PowerManager::class.java)
            val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            if (!exempt) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                )
            } else {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    })
                }
            }
        }
    }

    private fun testConnection() {
        val url = vm.serverUrl.value?.trimEnd('/') ?: ""
        val key = vm.apiKey.value ?: ""
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), "No server configured. Tap Change Server.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.testConnectionBtn.isEnabled = false
        binding.testConnectionBtn.text = "Testing…"

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val (ok, msg) = try {
                val ping = client.newCall(Request.Builder().url("$url/api/ping").build()).execute()
                ping.close()
                if (!ping.isSuccessful) throw Exception("Server unreachable (HTTP ${ping.code})")
                val auth = client.newCall(
                    Request.Builder().url("$url/api/folders")
                        .header("x-api-key", key).build()
                ).execute()
                val code = auth.code; auth.close()
                when (code) {
                    200  -> {
                        // Refresh direct upload URL from server config
                        try {
                            val cfg = client.newCall(
                                Request.Builder().url("$url/api/config")
                                    .header("x-api-key", key).build()
                            ).execute()
                            val body = cfg.body?.string(); cfg.close()
                            if (cfg.isSuccessful && body != null) {
                                val directUrl = org.json.JSONObject(body).optString("local_url", "")
                                prefs.setDirectUrl(directUrl)
                            }
                        } catch (_: Exception) {}
                        true  to "✓ Connected successfully!"
                    }
                    401  -> false to "✗ API key rejected"
                    else -> false to "✗ HTTP $code"
                }
            } catch (e: Exception) { false to "✗ ${e.message}" }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                binding.testConnectionBtn.isEnabled = true
                binding.testConnectionBtn.text = "Test Connection"
            }
        }
    }

    // ── Theme helpers ─────────────────────────────────────────────────────────
    private fun applyTheme(theme: String) {
        vm.setTheme(theme)
        val mode = when (theme) {
            "light"  -> AppCompatDelegate.MODE_NIGHT_NO
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else     -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        requireActivity().recreate()
    }

    private fun updateThemeButtons(theme: String) {
        // Swap background: active = filled primary, inactive = outlined
        val buttons = listOf(
            Triple(binding.themeDarkBtn,   "dark",   "Dark"),
            Triple(binding.themeSystemBtn, "system", "System"),
            Triple(binding.themeLightBtn,  "light",  "Light"),
        )
        buttons.forEach { (btn, key, label) ->
            val active = (key == theme || (key == "dark" && theme != "light" && theme != "system"))
                .let { if (key == theme) true else false }
            // Set filled bg for active, transparent outlined for inactive
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (key == theme)
                    com.google.android.material.color.MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnSurface)
                else
                    android.graphics.Color.TRANSPARENT
            )
            btn.setTextColor(
                if (key == theme)
                    com.google.android.material.color.MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurface)
                else
                    com.google.android.material.color.MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnSurface)
            )
            btn.strokeColor = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOutline)
            )
            btn.alpha = 1.0f
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
