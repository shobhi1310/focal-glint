package com.focal.ui.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.focal.service.LlmForegroundService
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.db.FocalDatabase
import com.focal.intelligence.EmbeddingProvider
import com.focal.intelligence.EngineWarmupCoordinator
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
import com.focal.intelligence.ModelBackendPolicy
import com.focal.worker.InferenceWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis
import javax.inject.Inject

data class SetupUiState(
    val notificationAccessGranted: Boolean = false,
    val batteryOptimizationDisabled: Boolean = false,
    val selectedModel: ModelVariant = ModelVariant.GEMMA4_E2B,
    val activeModel: ModelVariant? = null,
    val modelsOnDevice: Set<ModelVariant> = emptySet(),
    val downloadProgress: Int? = null,
    val engineRunning: Boolean = false,
    val useGpu: Boolean = true,
    val backendSwitching: Boolean = false,
    val engineStopping: Boolean = false,
    val databaseClearing: Boolean = false,
    val databaseMessage: String? = null,
    val errorMessage: String? = null,
    val embeddingModelAvailable: Boolean = false,
    val cloudEndpoint: String = "",
    val cloudApiKey: String = "",
    val cloudModelName: String = ""
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: FocalDatabase,
    private val modelManager: ModelManager,
    private val inferenceProvider: InferenceProvider,
    private val embeddingProvider: EmbeddingProvider,
    private val engineWarmupCoordinator: EngineWarmupCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        val activeModel = modelManager.activeVariant()
        val selected = modelManager.getSelectedVariant() ?: activeModel ?: _uiState.value.selectedModel
        val onDevice = ModelVariant.entries.filter { modelManager.isModelAvailable(it) }.toSet()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        _uiState.value = _uiState.value.copy(
            notificationAccessGranted = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName),
            batteryOptimizationDisabled = pm.isIgnoringBatteryOptimizations(context.packageName),
            selectedModel = selected,
            activeModel = activeModel,
            modelsOnDevice = onDevice,
            engineRunning = inferenceProvider.isReady(),
            useGpu = modelManager.getBackendPreference(),
            embeddingModelAvailable = modelManager.isGemmaEmbeddingAvailable,
            cloudEndpoint = modelManager.getCloudEndpoint(),
            cloudApiKey = modelManager.getCloudApiKey(),
            cloudModelName = modelManager.getCloudModelName()
        )
    }

    fun onSetCloudEndpoint(url: String) {
        modelManager.setCloudEndpoint(url)
        _uiState.value = _uiState.value.copy(cloudEndpoint = url)
    }

    fun onSetCloudApiKey(key: String) {
        modelManager.setCloudApiKey(key)
        _uiState.value = _uiState.value.copy(cloudApiKey = key)
    }

    fun onSetCloudModelName(name: String) {
        modelManager.setCloudModelName(name)
        _uiState.value = _uiState.value.copy(cloudModelName = name)
    }

    fun onRequestBatteryOptimization() {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    fun onOpenNotifSettings() {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    fun onModelSelected(variant: ModelVariant) {
        val alreadyOnDevice = modelManager.isModelAvailable(variant)
        modelManager.saveSelectedVariant(variant)
        _uiState.value = _uiState.value.copy(
            selectedModel = variant,
            activeModel = if (alreadyOnDevice) variant else null
        )
        if (inferenceProvider.isReady()) {
            _uiState.value = _uiState.value.copy(engineStopping = true)
            viewModelScope.launch {
                stopEngine("model switch to ${variant.name}")
                _uiState.value = _uiState.value.copy(engineRunning = false, engineStopping = false)
            }
        } else {
            _uiState.value = _uiState.value.copy(engineRunning = false)
        }
    }

    fun onClearDatabase() {
        if (_uiState.value.databaseClearing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(databaseClearing = true, databaseMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    database.clearAllTables()
                }
                _uiState.value = _uiState.value.copy(
                    databaseClearing = false,
                    databaseMessage = "Database cleared."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    databaseClearing = false,
                    databaseMessage = "Failed to clear database: ${e.message}"
                )
            }
        }
    }

    fun onDownload() {
        val variant = _uiState.value.selectedModel
        viewModelScope.launch { startDownload(variant) }
    }

    fun onRedownload() {
        val variant = _uiState.value.selectedModel
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(engineStopping = true)
            stopEngine("redownload ${variant.name}")
            modelManager.deleteModel(variant)
            _uiState.value = _uiState.value.copy(activeModel = null, engineRunning = false, engineStopping = false)
            startDownload(variant)
        }
    }

    private suspend fun startDownload(variant: ModelVariant) {
        _uiState.value = _uiState.value.copy(downloadProgress = 0, errorMessage = null)
        try {
            modelManager.downloadModel(variant) { progress ->
                _uiState.value = _uiState.value.copy(downloadProgress = progress)
            }
            _uiState.value = _uiState.value.copy(downloadProgress = null, activeModel = variant)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                downloadProgress = null,
                errorMessage = "Download failed: ${e.message}"
            )
        }
    }

    fun onStartEngine() {
        if (_uiState.value.engineStopping) return
        modelManager.setEngineEnabled(true)
        modelManager.setPendingRebuild(true)
        _uiState.value = _uiState.value.copy(errorMessage = null)
        viewModelScope.launch {
            try {
                val warmed = engineWarmupCoordinator.warmUp(recreateEmbeddings = true)
                if (warmed) {
                    context.startForegroundService(Intent(context, LlmForegroundService::class.java))
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        InferenceWorker.WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<InferenceWorker>().build()
                    )
                }
                _uiState.value = _uiState.value.copy(engineRunning = warmed)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    engineRunning = false,
                    errorMessage = "Engine start failed: ${e.message}"
                )
            }
        }
    }

    fun onStopEngine() {
        if (_uiState.value.engineStopping) return
        _uiState.value = _uiState.value.copy(engineStopping = true)
        viewModelScope.launch {
            stopEngine("manual stop")
            _uiState.value = _uiState.value.copy(engineRunning = false, engineStopping = false)
        }
    }

    private suspend fun cancelWorkerAndWait() {
        InferenceWorker.cancelAndWait(WorkManager.getInstance(context))
    }

    private suspend fun stopEngine(reason: String) {
        Log.i(TAG, "Engine stop requested: reason=$reason. Active conversation will be dropped.")
        val elapsedMs = measureTimeMillis {
            cancelWorkerAndWait()
            withContext(Dispatchers.IO) {
                inferenceProvider.close()
            }
            Log.i(TAG, "LLM close completed: reason=$reason isReady=${inferenceProvider.isReady()}")
            modelManager.setEngineEnabled(false)
            context.stopService(Intent(context, LlmForegroundService::class.java))
        }
        Log.i(TAG, "Engine stopped: reason=$reason elapsedMs=$elapsedMs")
    }

    fun onToggleBackend(useGpu: Boolean) {
        if (_uiState.value.backendSwitching) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(backendSwitching = true, useGpu = useGpu)
            modelManager.saveBackendPreference(useGpu)
            if (inferenceProvider.isReady()) {
                try {
                    val variant = _uiState.value.selectedModel
                    inferenceProvider.restart(
                        modelManager.modelFileFor(variant).absolutePath,
                        useGpu,
                        variant.maxContextTokens
                    )
                    reinitializeEmbeddings(useGpu)
                } catch (e: Exception) {
                    val fallback = !useGpu
                    modelManager.saveBackendPreference(fallback)
                    _uiState.value = _uiState.value.copy(
                        useGpu = fallback,
                        errorMessage = "Backend switch failed: ${e.message}"
                    )
                }
            } else {
                reinitializeEmbeddings(useGpu)
            }
            _uiState.value = _uiState.value.copy(backendSwitching = false)
        }
    }

    private suspend fun reinitializeEmbeddings(useGpu: Boolean) {
        if (!modelManager.isGemmaEmbeddingAvailable) return
        if (embeddingProvider.isReady()) {
            withContext(Dispatchers.IO) { embeddingProvider.close() }
        }
        embeddingProvider.initialize(
            modelManager.gemmaEmbeddingModelFile.absolutePath,
            "",
            ModelBackendPolicy.useGpuForEmbeddings(useGpu)
        )
    }

    companion object {
        private const val TAG = "SetupViewModel"
    }
}
