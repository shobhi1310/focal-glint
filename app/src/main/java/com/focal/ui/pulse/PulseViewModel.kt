package com.focal.ui.pulse

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.WidgetRepository
import com.focal.worker.ClassificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PulseUiState(
    val configs: List<WidgetConfigEntity> = emptyList(),
    val states: Map<String, WidgetStateEntity> = emptyMap(),
    val isRefreshing: Boolean = false,
    val showWizard: Boolean = false
)

@HiltViewModel
class PulseViewModel @Inject constructor(
    private val widgetRepository: WidgetRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val showWizard = MutableStateFlow(false)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<PulseUiState> = combine(
        widgetRepository.observeConfigs(),
        widgetRepository.observeStates(),
        isRefreshing,
        showWizard
    ) { values ->
        val configs = values[0] as List<WidgetConfigEntity>
        val statesList = values[1] as List<WidgetStateEntity>
        val refreshing = values[2] as Boolean
        val wizard = values[3] as Boolean
        PulseUiState(
            configs = configs,
            states = statesList.associateBy { it.widgetId },
            isRefreshing = refreshing,
            showWizard = wizard
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PulseUiState())

    fun onRefresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            WorkManager.getInstance(context).enqueueUniqueWork(
                ClassificationWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ClassificationWorker>().build()
            )
            kotlinx.coroutines.delay(3000)
            isRefreshing.value = false
        }
    }

    fun onShowWizard() { showWizard.value = true }
    fun onDismissWizard() { showWizard.value = false }

    fun onCreateWidget(config: WidgetConfigEntity) {
        viewModelScope.launch {
            widgetRepository.createWidget(config)
            showWizard.value = false
            onRefresh()
        }
    }

    fun onDeleteWidget(widgetId: String) {
        viewModelScope.launch { widgetRepository.deleteWidget(widgetId) }
    }

    fun onWipeWidget(widgetId: String) {
        viewModelScope.launch { widgetRepository.wipeWidgetData(widgetId) }
    }
}
