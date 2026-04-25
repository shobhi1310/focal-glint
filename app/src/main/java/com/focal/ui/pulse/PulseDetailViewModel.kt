package com.focal.ui.pulse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.WidgetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PulseDetailUiState(
    val config: WidgetConfigEntity? = null,
    val state: WidgetStateEntity? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class PulseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val widgetRepository: WidgetRepository
) : ViewModel() {
    private val widgetId: String = savedStateHandle.get<String>("widgetId") ?: ""
    private val _uiState = MutableStateFlow(PulseDetailUiState())
    val uiState: StateFlow<PulseDetailUiState> = _uiState.asStateFlow()

    init {
        loadWidget()
        observeState()
    }

    private fun loadWidget() {
        viewModelScope.launch {
            val config = widgetRepository.getConfig(widgetId)
            _uiState.value = _uiState.value.copy(config = config, isLoading = false)
        }
    }

    private fun observeState() {
        viewModelScope.launch {
            widgetRepository.observeState(widgetId).collect { state ->
                _uiState.value = _uiState.value.copy(state = state)
            }
        }
    }

    fun onDelete() {
        viewModelScope.launch { widgetRepository.deleteWidget(widgetId) }
    }

    fun onWipe() {
        viewModelScope.launch { widgetRepository.wipeWidgetData(widgetId) }
    }
}
