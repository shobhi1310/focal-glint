package com.focal.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
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
    val errorMessage: String? = null,
    val embeddingModelAvailable: Boolean = false,
    val embeddingDownloadProgress: Int? = null,
    val embeddingErrorMessage: String? = null
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val inferenceProvider: InferenceProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        val selected = _uiState.value.selectedModel
        val selectedOnDevice = modelManager.isModelAvailable(selected)
        val onDevice = ModelVariant.entries.filter { modelManager.isModelAvailable(it) }.toSet()
        _uiState.value = _uiState.value.copy(
            notificationAccessGranted = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName),
            activeModel = if (selectedOnDevice) selected else null,
            modelsOnDevice = onDevice,
            engineRunning = inferenceProvider.isReady(),
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
        if (inferenceProvider.isReady()) {
            inferenceProvider.close()
        }
        _uiState.value = _uiState.value.copy(
            selectedModel = variant,
            activeModel = if (alreadyOnDevice) variant else null,
            engineRunning = false
        )
    }

    fun onDownload() {
        val variant = _uiState.value.selectedModel
        viewModelScope.launch { startDownload(variant) }
    }

    fun onRedownload() {
        val variant = _uiState.value.selectedModel
        viewModelScope.launch {
            inferenceProvider.close()
            modelManager.deleteModel(variant)
            _uiState.value = _uiState.value.copy(activeModel = null, engineRunning = false)
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
        viewModelScope.launch {
            try {
                val variant = _uiState.value.selectedModel
                inferenceProvider.initialize(
                    modelManager.modelFileFor(variant).absolutePath,
                    maxContextTokens = variant.maxContextTokens
                )
                _uiState.value = _uiState.value.copy(engineRunning = true, errorMessage = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to start engine: ${e.message}")
            }
        }
    }

    fun onStopEngine() {
        inferenceProvider.close()
        _uiState.value = _uiState.value.copy(engineRunning = false)
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
