package com.focal.ui.all

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

data class AllNotificationsUiState(
    val urgent: List<NotificationEntity> = emptyList(),
    val actionable: List<NotificationEntity> = emptyList(),
    val digest: List<NotificationEntity> = emptyList(),
    val noise: List<NotificationEntity> = emptyList(),
    val totalCount: Int = 0
)

@HiltViewModel
class AllNotificationsViewModel @Inject constructor(
    notificationRepository: NotificationRepository
) : ViewModel() {

    private val categoriesFlow = combine(
        notificationRepository.getByCategory(ClassificationResult.URGENT),
        notificationRepository.getByCategory(ClassificationResult.ACTIONABLE),
        notificationRepository.getByCategory(ClassificationResult.DIGEST),
        notificationRepository.getByCategory(ClassificationResult.NOISE)
    ) { urgent, actionable, digest, noise ->
        listOf(urgent, actionable, digest, noise)
    }

    val uiState: StateFlow<AllNotificationsUiState> = combine(
        categoriesFlow,
        notificationRepository.totalCount()
    ) { categories, total ->
        AllNotificationsUiState(
            urgent = categories[0],
            actionable = categories[1],
            digest = categories[2],
            noise = categories[3],
            totalCount = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AllNotificationsUiState()
    )
}
