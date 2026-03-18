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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import com.simplesync.companion.data.prefs.Prefs
import com.simplesync.companion.databinding.FragmentSettingsBinding
import com.simplesync.companion.repository.SyncRepository
import androidx.navigation.fragment.findNavController
import com.simplesync.companion.R
import com.simplesync.companion.worker.ScanWorker
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs.get(app)
    var savedScrollY: Int = 0
    val notifsOn:  LiveData<Boolean> = prefs.notifsOn.asLiveData()
    val appTheme:  LiveData<String>  = prefs.appTheme.asLiveData()
    fun setNotifs(on: Boolean) = viewModelScope.launch { prefs.setNotifsOn(on) }
    fun setTheme(t: String)    = viewModelScope.launch { prefs.setAppTheme(t) }
}

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: SettingsViewModel by viewModels()
    private val prefs by lazy { Prefs.get(requireContext()) }
    private val repo  by lazy { SyncRepository.get(requireContext()) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (vm.savedScrollY > 0) {
            binding.root.post {
                binding.root.scrollTo(0, vm.savedScrollY)
                vm.savedScrollY = 0
            }
        }

        vm.notifsOn.observe(viewLifecycleOwner) { binding.notifsSwitch.isChecked = it }
        binding.notifsSwitch.setOnCheckedChangeListener { _, checked -> vm.setNotifs(checked) }

        vm.appTheme.observe(viewLifecycleOwner) { updateThemeButtons(it) }
        binding.themeDarkBtn.setOnClickListener   { applyTheme("dark") }
        binding.themeSystemBtn.setOnClickListener { applyTheme("system") }
        binding.themeLightBtn.setOnClickListener  { applyTheme("light") }

        binding.syncNowBtn.setOnClickListener {
            ScanWorker.runNow(requireContext())
            Toast.makeText(requireContext(), "Scan started…", Toast.LENGTH_SHORT).show()
            findNavController().navigate(R.id.queueFragment)
        }

        binding.batteryBtn.setOnClickListener { openBatterySettings() }
        binding.checkIntegrityBtn.setOnClickListener { checkIntegrityStatus() }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryStatus()
    }

    private fun checkIntegrityStatus() {
        binding.checkIntegrityBtn.isEnabled = false
        binding.checkIntegrityBtn.text = "Checking…"
        binding.integrityStatusText.isVisible = false

        lifecycleScope.launch {
            val flagSet = repo.checkIntegrityFlag()

            if (!flagSet) {
                showIntegrityResult(
                    "✓ No changes detected — all server records are in sync.",
                    isWarning = false
                )
            } else {
                repo.clearAllTrackedFiles()
                repo.acknowledgeIntegrityFlag()
                ScanWorker.runNow(requireContext())

                showIntegrityResult(
                    "⚠ Server integrity changes detected. Local upload history cleared — scanning now. Files still on the server will be marked as Already on server; deleted files will be re-uploaded.",
                    isWarning = true
                )
            }

            binding.checkIntegrityBtn.isEnabled = true
            binding.checkIntegrityBtn.text = "Check Integrity Status"
        }
    }

    private fun showIntegrityResult(message: String, isWarning: Boolean) {
        binding.integrityStatusText.text = message
        binding.integrityStatusText.setTextColor(
            if (isWarning)
                com.google.android.material.color.MaterialColors.getColor(
                    binding.integrityStatusText,
                    com.google.android.material.R.attr.colorError
                )
            else
                com.google.android.material.color.MaterialColors.getColor(
                    binding.integrityStatusText,
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                )
        )
        binding.integrityStatusText.isVisible = true
    }

    private fun updateBatteryStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.batteryStatusText.text = "Not required on this Android version"
            binding.batteryBtn.isEnabled = false
            return
        }
        val pm = requireContext().getSystemService(PowerManager::class.java)
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
            val pm = ctx.getSystemService(PowerManager::class.java)
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

    private fun applyTheme(theme: String) {
        vm.setTheme(theme)
        val mode = when (theme) {
            "light"  -> AppCompatDelegate.MODE_NIGHT_NO
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else     -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        vm.savedScrollY = binding.root.scrollY
        requireActivity().recreate()
    }

    private fun updateThemeButtons(theme: String) {
        val buttons = listOf(
            Triple(binding.themeDarkBtn,   "dark",   "Dark"),
            Triple(binding.themeSystemBtn, "system", "System"),
            Triple(binding.themeLightBtn,  "light",  "Light"),
        )
        buttons.forEach { (btn, key, _) ->
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
