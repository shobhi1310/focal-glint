package com.focal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.focal.ui.navigation.FocalNavigation
import com.focal.ui.theme.FocalTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryExemptionIfNeeded()
        setContent {
            FocalTheme {
                FocalNavigation()
            }
        }
    }

    private fun requestBatteryExemptionIfNeeded() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val prefs = getSharedPreferences("focal_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("battery_exemption_prompted", false)) {
                prefs.edit().putBoolean("battery_exemption_prompted", true).apply()
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }
}
