package com.focal

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.DefaultRules
import com.focal.intelligence.EmbeddingProvider
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.focal.worker.DailyResetWorker
import javax.inject.Inject

@HiltAndroidApp
class FocalApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var ruleRepository: RuleRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var inferenceProvider: InferenceProvider

    @Inject
    lateinit var embeddingProvider: EmbeddingProvider

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        seedDefaultRules()
        initializeLlmIfModelExists()
        scheduleDailyReset()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun seedDefaultRules() {
        val prefs = getSharedPreferences("focal_prefs", MODE_PRIVATE)
        val currentVersion = 5
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

        val useGpu = modelManager.getBackendPreference()
        val variant = modelManager.activeVariant() ?: ModelVariant.GEMMA4_E2B
        applicationScope.launch {
            try {
                inferenceProvider.initialize(modelManager.modelPath, useGpu, variant.maxContextTokens)
                Log.d(TAG, "LLM engine initialized successfully (gpu=$useGpu, context=${variant.maxContextTokens})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize LLM engine", e)
            }
        }

        if (modelManager.isEmbeddingModelAvailable) {
            applicationScope.launch {
                try {
                    embeddingProvider.initialize(
                        modelManager.geckoModelFile.absolutePath,
                        modelManager.geckoTokenizerFile.absolutePath,
                        modelManager.getBackendPreference()
                    )
                    Log.d(TAG, "Embedding model initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize embedding model", e)
                }
            }
        } else {
            Log.d(TAG, "Embedding model not found at ${modelManager.embeddingModelDir}")
        }
    }

    private fun scheduleDailyReset() {
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelay = target.timeInMillis - now.timeInMillis

        val request = androidx.work.PeriodicWorkRequestBuilder<DailyResetWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyResetWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        private const val TAG = "FocalApp"
    }
}
