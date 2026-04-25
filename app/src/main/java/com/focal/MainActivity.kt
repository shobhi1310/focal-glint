package com.focal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.focal.intelligence.EngineWarmupCoordinator
import com.focal.intelligence.ModelManager
import com.focal.service.LlmForegroundService
import com.focal.ui.navigation.FocalNavigation
import com.focal.ui.theme.FocalTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.content.edit
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var modelManager: ModelManager
    @Inject lateinit var engineWarmupCoordinator: EngineWarmupCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryExemptionIfNeeded()
        warmEngineIfEnabled()
        setContent {
            FocalTheme {
                FocalNavigation()
            }
        }
    }

    private fun warmEngineIfEnabled() {
        if (!modelManager.isEngineEnabled()) return
        lifecycleScope.launch {
            try {
                if (engineWarmupCoordinator.warmUp()) {
                    startForegroundService(Intent(this@MainActivity, LlmForegroundService::class.java))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Visible engine warm-up failed", e)
            }
        }
    }

    private fun requestBatteryExemptionIfNeeded() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val prefs = getSharedPreferences("focal_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("battery_exemption_prompted", false)) {
                prefs.edit { putBoolean("battery_exemption_prompted", true) }
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
