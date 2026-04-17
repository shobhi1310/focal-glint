package com.focal.ui.digest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.NotificationRepository
import com.focal.intelligence.ClassificationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DigestUiState(
    val urgent: List<NotificationEntity> = emptyList(),
    val informational: List<NotificationEntity> = emptyList(),
    val noise: List<NotificationEntity> = emptyList(),
    val totalCount: Int = 0,
    val urgentCount: Int = 0
)

@HiltViewModel
class DigestViewModel @Inject constructor(
    notificationRepository: NotificationRepository
) : ViewModel() {

    val uiState: StateFlow<DigestUiState> = combine(
        notificationRepository.getByCategory(ClassificationResult.URGENT),
        notificationRepository.getByCategory(ClassificationResult.INFORMATIONAL),
        notificationRepository.getByCategory(ClassificationResult.NOISE),
        notificationRepository.totalCount()
    ) { urgent, informational, noise, total ->
        DigestUiState(
            urgent = urgent,
            informational = informational,
            noise = noise,
            totalCount = total,
            urgentCount = urgent.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DigestUiState()
    )
}
