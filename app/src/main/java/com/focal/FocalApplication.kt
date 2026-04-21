package com.focal

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Configuration
import androidx.work.WorkManager
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.DefaultRules
import com.focal.intelligence.EmbeddingProvider
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelBackendPolicy
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
import com.focal.intelligence.TopicClusteringPolicy
import com.focal.intelligence.TopicEngine
import com.focal.ui.theme.ThemePreference
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.focal.worker.ClassificationWorker
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
        ThemePreference.initialize(this)
        DebugLogger.init(this)
        seedDefaultRules()
        migrateTopicClusteringIfNeeded()
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

        // Initialize LLM
        val variant = modelManager.activeVariant()
        if (!modelManager.isEngineEnabled()) {
            Log.d(TAG, "LLM auto-start disabled by preference")
        } else if (variant != null) {
            val modelFile = modelManager.modelFileFor(variant)
            val useGpu = modelManager.getBackendPreference()
            Log.d(TAG, "Found model: ${variant.displayName} at ${modelFile.absolutePath} (${modelFile.length() / 1_000_000}MB, gpu=$useGpu)")
            applicationScope.launch {
                try {
                    inferenceProvider.initialize(modelFile.absolutePath, useGpu, variant.maxContextTokens)
                    Log.d(TAG, "LLM engine initialized: ${variant.displayName}")
                } catch (e: Exception) {
                    Log.e(TAG, "LLM init failed (${e.javaClass.simpleName}): ${e.message}")
                    if (useGpu) {
                        Log.d(TAG, "Retrying with CPU backend")
                        try {
                            inferenceProvider.initialize(modelFile.absolutePath, false, variant.maxContextTokens)
                            Log.d(TAG, "LLM engine initialized with CPU fallback")
                        } catch (e2: Exception) {
                            Log.e(TAG, "CPU init also failed — model likely corrupt. Deleting: ${e2.message}")
                            modelManager.deleteModel(variant)
                        }
                    } else {
                        Log.e(TAG, "LLM init failed — model likely corrupt. Deleting: ${e.message}")
                        modelManager.deleteModel(variant)
                    }
                }
            }
        } else {
            Log.d(TAG, "No LLM model found at ${modelManager.modelDir}")
        }

        // Initialize embedding model (independent of LLM)
        if (modelManager.isEmbeddingModelAvailable) {
            applicationScope.launch {
                try {
                    val useGpu = ModelBackendPolicy.useGpuForEmbeddings(
                        llmUseGpu = modelManager.getBackendPreference()
                    )
                    embeddingProvider.initialize(
                        modelManager.geckoModelFile.absolutePath,
                        modelManager.geckoTokenizerFile.absolutePath,
                        useGpu
                    )
                    Log.d(TAG, "Embedding model initialized successfully (gpu=$useGpu)")
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

    private fun migrateTopicClusteringIfNeeded() {
        val prefs = getSharedPreferences("focal_prefs", MODE_PRIVATE)
        val storedVersion = prefs.getInt("topic_clustering_config_version", 0)
        if (!TopicClusteringPolicy.needsFullRebuild(storedVersion)) return

        TopicEngine.pendingFullRebuild.set(true)
        val request = OneTimeWorkRequestBuilder<ClassificationWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            ClassificationWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        prefs.edit()
            .putInt("topic_clustering_config_version", TopicClusteringPolicy.CONFIG_VERSION)
            .apply()
    }

    companion object {
        private const val TAG = "FocalApp"
    }
}
