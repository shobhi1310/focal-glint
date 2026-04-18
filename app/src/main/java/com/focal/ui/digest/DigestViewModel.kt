package com.focal.ui.digest

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.NotificationRepository
import com.focal.intelligence.ClassificationResult
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

data class DigestUiState(
    val urgent: List<NotificationEntity> = emptyList(),
    val informational: List<NotificationEntity> = emptyList(),
    val noise: List<NotificationEntity> = emptyList(),
    val totalCount: Int = 0,
    val urgentCount: Int = 0,
    val isProcessing: Boolean = false
)

@HiltViewModel
class DigestViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val isProcessing = MutableStateFlow(false)

    val uiState: StateFlow<DigestUiState> = combine(
        notificationRepository.getByCategory(ClassificationResult.URGENT),
        notificationRepository.getByCategory(ClassificationResult.INFORMATIONAL),
        notificationRepository.getByCategory(ClassificationResult.NOISE),
        notificationRepository.totalCount(),
        isProcessing
    ) { urgent, informational, noise, total, processing ->
        DigestUiState(
            urgent = urgent,
            informational = informational,
            noise = noise,
            totalCount = total,
            urgentCount = urgent.size,
            isProcessing = processing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DigestUiState()
    )

    fun onRefresh() {
        viewModelScope.launch {
            isProcessing.value = true
            val workRequest = OneTimeWorkRequestBuilder<ClassificationWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ClassificationWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            isProcessing.value = false
        }
    }
}
