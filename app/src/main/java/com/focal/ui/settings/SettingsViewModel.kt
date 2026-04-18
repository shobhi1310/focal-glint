package com.focal.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.worker.ClassificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    val snackbarMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val notificationRepository: NotificationRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private var debounceJob: Job? = null
    private val pendingChanges = mutableMapOf<String, String?>()

    init {
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
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        _uiState.value = _uiState.value.copy(snackbarMessage = "Changes saved · refreshing digest...")

        delay(2000)
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }
}
