package com.focal.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.EmbeddingProvider
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelBackendPolicy
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
import com.focal.intelligence.TopicEngine
import com.focal.worker.ClassificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppOverride(
    val packageName: String,
    val appName: String,
    val notificationCount: Int,
    val systemDefault: String?,
    val userOverride: String?
)

data class SettingsUiState(
    val apps: List<AppOverride> = emptyList(),
    val snackbarMessage: String? = null,
    val useGpu: Boolean = true,
    val engineRestarting: Boolean = false,
    val isEmbeddingModelAvailable: Boolean = false,
    val isEmbeddingReady: Boolean = false,
    val isEmbeddingInitializing: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val notificationRepository: NotificationRepository,
    private val inferenceProvider: InferenceProvider,
    private val embeddingProvider: EmbeddingProvider,
    private val modelManager: ModelManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private var debounceJob: Job? = null
    private val pendingChanges = mutableMapOf<String, String?>()

    init {
        _uiState.value = _uiState.value.copy(
            useGpu = modelManager.getBackendPreference(),
            isEmbeddingModelAvailable = modelManager.isGemmaEmbeddingAvailable,
            isEmbeddingReady = embeddingProvider.isReady()
        )
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            val profiles = notificationRepository.getAppProfiles().first()
            val userRules = ruleRepository.getUserOverrides()
            val systemRules = ruleRepository.getSystemDefaults()

            val userRuleMap = userRules
                .filter { it.type == "app_match" && it.app != null }
                .associateBy { it.app!! }
            val systemRuleMap = systemRules
                .filter { it.type == "app_match" && it.app != null }
                .associateBy { it.app!! }

            val apps = profiles
                .sortedByDescending { it.notificationCount }
                .map { profile ->
                    AppOverride(
                        packageName = profile.packageName,
                        appName = profile.appName,
                        notificationCount = profile.notificationCount,
                        systemDefault = systemRuleMap[profile.packageName]?.category,
                        userOverride = userRuleMap[profile.packageName]?.category
                    )
                }

            _uiState.value = _uiState.value.copy(apps = apps)
        }
    }

    fun onToggle(packageName: String, newState: String?) {
        _uiState.value = _uiState.value.copy(
            apps = _uiState.value.apps.map { app ->
                if (app.packageName == packageName) app.copy(userOverride = newState)
                else app
            }
        )

        pendingChanges[packageName] = newState

        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(3000)
            persistChanges()
        }
    }

    fun setBackendPreference(useGpu: Boolean) {
        if (_uiState.value.engineRestarting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(engineRestarting = true, useGpu = useGpu)
            modelManager.saveBackendPreference(useGpu)
            if (modelManager.isEngineEnabled() && modelManager.isModelAvailable) {
                try {
                    ClassificationWorker.cancelAndWait(WorkManager.getInstance(context))
                    val variant = modelManager.activeVariant() ?: ModelVariant.GEMMA4_E2B
                    inferenceProvider.restart(modelManager.modelPath, useGpu, variant.maxContextTokens)
                    reinitializeEmbeddings(useGpu)
                } catch (e: Exception) {
                    val fallback = !useGpu
                    modelManager.saveBackendPreference(fallback)
                    _uiState.value = _uiState.value.copy(
                        useGpu = fallback,
                        snackbarMessage = "Backend switch failed, reverted."
                    )
                }
            } else {
                reinitializeEmbeddings(useGpu)
            }
            _uiState.value = _uiState.value.copy(engineRestarting = false)
        }
    }

    private suspend fun reinitializeEmbeddings(useGpu: Boolean) {
        if (!modelManager.isGemmaEmbeddingAvailable) return
        if (embeddingProvider.isReady()) {
            withContext(Dispatchers.IO) { embeddingProvider.close() }
        }
        embeddingProvider.initialize(modelManager.gemmaEmbeddingModelFile.absolutePath, "", useGpu)
    }

    fun refreshEmbeddingState() {
        _uiState.value = _uiState.value.copy(
            isEmbeddingModelAvailable = modelManager.isGemmaEmbeddingAvailable,
            isEmbeddingReady = embeddingProvider.isReady()
        )
    }

    fun initializeEmbedding() {
        if (_uiState.value.isEmbeddingInitializing || !_uiState.value.isEmbeddingModelAvailable) return
        if (embeddingProvider.isReady()) {
            _uiState.value = _uiState.value.copy(isEmbeddingReady = true)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isEmbeddingInitializing = true)
            try {
                reinitializeEmbeddings(modelManager.getBackendPreference())
                _uiState.value = _uiState.value.copy(
                    isEmbeddingReady = true,
                    snackbarMessage = "Embedding engine started."
                )
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to start embedding engine", e)
                _uiState.value = _uiState.value.copy(snackbarMessage = "Failed to start embedding engine.")
            } finally {
                _uiState.value = _uiState.value.copy(isEmbeddingInitializing = false)
            }
        }
    }

    fun dismissSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    private suspend fun persistChanges() {
        val changes = pendingChanges.toMap()
        pendingChanges.clear()

        for ((packageName, category) in changes) {
            if (category != null) {
                ruleRepository.setUserOverride(packageName, category)
            } else {
                ruleRepository.clearUserOverride(packageName)
            }
        }

        val workRequest = OneTimeWorkRequestBuilder<ClassificationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ClassificationWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        _uiState.value = _uiState.value.copy(snackbarMessage = "Changes saved · refreshing digest...")

        delay(2000)
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }
}
