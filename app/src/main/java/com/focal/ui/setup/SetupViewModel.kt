package com.focal.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
import com.focal.worker.ClassificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val notificationAccessGranted: Boolean = false,
    val selectedModel: ModelVariant = ModelVariant.GEMMA3_1B,
    val activeModel: ModelVariant? = null,
    val modelsOnDevice: Set<ModelVariant> = emptySet(),
    val downloadProgress: Int? = null,
    val engineRunning: Boolean = false,
    val useGpu: Boolean = true,
    val backendSwitching: Boolean = false,
    val engineStopping: Boolean = false,
    val errorMessage: String? = null,
    val embeddingModelAvailable: Boolean = false,
    val embeddingDownloadProgress: Int? = null,
    val embeddingErrorMessage: String? = null
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val inferenceProvider: InferenceProvider
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
        _uiState.value = _uiState.value.copy(
            notificationAccessGranted = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName),
            selectedModel = selected,
            activeModel = activeModel,
            modelsOnDevice = onDevice,
            engineRunning = inferenceProvider.isReady(),
            useGpu = modelManager.getBackendPreference(),
            embeddingModelAvailable = modelManager.isEmbeddingModelAvailable
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
                cancelWorkerAndWait()
                inferenceProvider.close()
                modelManager.setEngineEnabled(false)
                _uiState.value = _uiState.value.copy(engineRunning = false, engineStopping = false)
            }
        } else {
            _uiState.value = _uiState.value.copy(engineRunning = false)
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
            cancelWorkerAndWait()
            inferenceProvider.close()
            modelManager.setEngineEnabled(false)
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
        viewModelScope.launch {
            try {
                val variant = _uiState.value.selectedModel
                inferenceProvider.initialize(
                    modelManager.modelFileFor(variant).absolutePath,
                    useGpu = _uiState.value.useGpu,
                    maxContextTokens = variant.maxContextTokens
                )
                modelManager.setEngineEnabled(true)
                _uiState.value = _uiState.value.copy(engineRunning = true, errorMessage = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to start engine: ${e.message}")
            }
        }
    }

    fun onStopEngine() {
        if (_uiState.value.engineStopping) return
        _uiState.value = _uiState.value.copy(engineStopping = true)
        viewModelScope.launch {
            cancelWorkerAndWait()
            inferenceProvider.close()
            modelManager.setEngineEnabled(false)
            _uiState.value = _uiState.value.copy(engineRunning = false, engineStopping = false)
        }
    }

    private suspend fun cancelWorkerAndWait() {
        ClassificationWorker.cancelAndWait(WorkManager.getInstance(context))
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
                } catch (e: Exception) {
                    val fallback = !useGpu
                    modelManager.saveBackendPreference(fallback)
                    _uiState.value = _uiState.value.copy(
                        useGpu = fallback,
                        errorMessage = "Backend switch failed: ${e.message}"
                    )
                }
            }
            _uiState.value = _uiState.value.copy(backendSwitching = false)
        }
    }

    fun onDownloadEmbeddingModel() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(embeddingDownloadProgress = 0, embeddingErrorMessage = null)
            try {
                modelManager.downloadEmbeddingModel { progress ->
                    _uiState.value = _uiState.value.copy(embeddingDownloadProgress = progress)
                }
                _uiState.value = _uiState.value.copy(
                    embeddingDownloadProgress = null,
                    embeddingModelAvailable = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    embeddingDownloadProgress = null,
                    embeddingErrorMessage = "Download failed: ${e.message}"
                )
            }
        }
    }
}
