package com.focal

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.DefaultRules
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FocalApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var ruleRepository: RuleRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var inferenceProvider: InferenceProvider

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        seedDefaultRules()
        initializeLlmIfModelExists()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun seedDefaultRules() {
        val prefs = getSharedPreferences("focal_prefs", MODE_PRIVATE)
        val currentVersion = 3
        if (prefs.getInt("rules_seed_version", 0) < currentVersion) {
            applicationScope.launch {
                ruleRepository.replaceSystemDefaults(DefaultRules.get())
                prefs.edit().putInt("rules_seed_version", currentVersion).apply()
            }
        }
    }

    private fun initializeLlmIfModelExists() {
        val modelManager = ModelManager(this)
        modelManager.ensureModelDir()

        if (!modelManager.isModelAvailable) {
            Log.d(TAG, "Model not found at ${modelManager.modelPath}. LLM unavailable.")
            Log.d(
                TAG,
                "To enable LLM, push model via: adb push ${ModelManager.MODEL_FILENAME}" +
                    " /data/data/com.focal/files/models/"
            )
            return
        }

        applicationScope.launch {
            try {
                inferenceProvider.initialize(modelManager.modelPath)
                Log.d(TAG, "LLM engine initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize LLM engine", e)
            }
        }
    }

    companion object {
        private const val TAG = "FocalApp"
    }
}
