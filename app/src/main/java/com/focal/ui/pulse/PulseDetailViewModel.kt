package com.focal.ui.pulse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.TransactionEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.TransactionRepository
import com.focal.data.repository.WidgetRepository
import com.focal.intelligence.WidgetComputeEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PulseDetailUiState(
    val config: WidgetConfigEntity? = null,
    val state: WidgetStateEntity? = null,
    val rows: List<ExtractedDataEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val isLoading: Boolean = true
)

sealed class PulseDetailEvent {
    data class ShowUndo(val message: String) : PulseDetailEvent()
}

@HiltViewModel
class PulseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val widgetRepository: WidgetRepository,
    private val widgetComputeEngine: WidgetComputeEngine,
    private val transactionRepository: TransactionRepository
) : ViewModel() {
    private val widgetId: String = savedStateHandle.get<String>("widgetId") ?: ""
    private val _uiState = MutableStateFlow(PulseDetailUiState())
    val uiState: StateFlow<PulseDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PulseDetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PulseDetailEvent> = _events.asSharedFlow()

    private var pendingDelete: ExtractedDataEntity? = null
    private var pendingDeleteTransaction: TransactionEntity? = null
    private var deleteJob: Job? = null

    init {
        loadWidget()
        observeState()
    }

    private fun loadWidget() {
        viewModelScope.launch {
            val config = widgetRepository.getConfig(widgetId)
            val rows = if (config != null && config.category != "finance") {
                widgetRepository.getExtractedData(config.category)
                    .filter { widgetComputeEngine.isValidRow(it, config.category) }
            } else emptyList()
            val transactions = if (config != null && config.category == "finance") {
                transactionRepository.getAll()
            } else emptyList()
            _uiState.value = _uiState.value.copy(
                config = config,
                rows = rows,
                transactions = transactions,
                isLoading = false
            )
        }
    }

    private fun observeState() {
        viewModelScope.launch {
            widgetRepository.observeState(widgetId).collect { state ->
                _uiState.value = _uiState.value.copy(state = state)
            }
        }
    }

    fun onDismissRow(row: ExtractedDataEntity) {
        commitPendingDelete()

        pendingDelete = row
        _uiState.value = _uiState.value.copy(
            rows = _uiState.value.rows.filter { it.id != row.id }
        )
        _events.tryEmit(PulseDetailEvent.ShowUndo("Removed"))

        deleteJob = viewModelScope.launch {
            delay(3000)
            commitPendingDelete()
        }
    }

    fun onUndo() {
        deleteJob?.cancel()
        val restored = pendingDelete ?: return
        pendingDelete = null
        _uiState.value = _uiState.value.copy(
            rows = (_uiState.value.rows + restored).sortedByDescending { it.extractedAt }
        )
    }

    fun onDismissTransaction(txn: TransactionEntity) {
        commitPendingDelete()
        pendingDeleteTransaction = txn
        _uiState.value = _uiState.value.copy(
            transactions = _uiState.value.transactions.filter { it.id != txn.id }
        )
        _events.tryEmit(PulseDetailEvent.ShowUndo("Removed"))
        deleteJob = viewModelScope.launch {
            delay(3000)
            commitPendingDelete()
        }
    }

    fun onUndoTransaction() {
        deleteJob?.cancel()
        val restored = pendingDeleteTransaction ?: return
        pendingDeleteTransaction = null
        _uiState.value = _uiState.value.copy(
            transactions = (_uiState.value.transactions + restored).sortedByDescending { it.postedAt }
        )
    }

    private fun commitPendingDelete() {
        pendingDeleteTransaction?.let { txn ->
            pendingDeleteTransaction = null
            deleteJob?.cancel()
            viewModelScope.launch {
                transactionRepository.deleteById(txn.id)
                widgetComputeEngine.computeAll()
                reloadData()
            }
            return
        }

        val toDelete = pendingDelete ?: return
        pendingDelete = null
        deleteJob?.cancel()
        viewModelScope.launch {
            widgetRepository.deleteExtractedRow(toDelete.id)
            widgetComputeEngine.computeAll()
            reloadData()
        }
    }

    private suspend fun reloadData() {
        val config = _uiState.value.config ?: return
        if (config.category == "finance") {
            val transactions = transactionRepository.getAll()
            _uiState.value = _uiState.value.copy(transactions = transactions)
        } else {
            val rows = widgetRepository.getExtractedData(config.category)
                .filter { widgetComputeEngine.isValidRow(it, config.category) }
            _uiState.value = _uiState.value.copy(rows = rows)
        }
    }

    fun onDelete() {
        viewModelScope.launch { widgetRepository.deleteWidget(widgetId) }
    }

    fun onWipe() {
        viewModelScope.launch {
            widgetRepository.wipeWidgetData(widgetId)
            widgetComputeEngine.computeAll()
            reloadData()
        }
    }
}
