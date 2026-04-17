package com.focal

import android.app.Application
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.DefaultRules
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FocalApplication : Application() {

    @Inject
    lateinit var ruleRepository: RuleRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        seedDefaultRules()
    }

    private fun seedDefaultRules() {
        val prefs = getSharedPreferences("focal_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("rules_seeded", false)) {
            applicationScope.launch {
                ruleRepository.addRules(DefaultRules.get())
                prefs.edit().putBoolean("rules_seeded", true).apply()
            }
        }
    }
}
