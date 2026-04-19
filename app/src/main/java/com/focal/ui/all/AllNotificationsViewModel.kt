package com.focal.ui.all

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.NotificationRepository
import com.focal.intelligence.ClassificationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AllNotificationsUiState(
    val matters: List<NotificationEntity> = emptyList(),
    val noise: List<NotificationEntity> = emptyList(),
    val uncategorized: List<NotificationEntity> = emptyList(),
    val totalCount: Int = 0
)

@HiltViewModel
class AllNotificationsViewModel @Inject constructor(
    notificationRepository: NotificationRepository
) : ViewModel() {

    companion object {
        private const val MAX_PER_CATEGORY = 20
    }

    val uiState: StateFlow<AllNotificationsUiState> = notificationRepository.getRecentNotifications()
        .map { all ->
            AllNotificationsUiState(
                matters = all.filter { it.category == ClassificationResult.MATTERS }.take(MAX_PER_CATEGORY),
                noise = all.filter { it.category == ClassificationResult.NOISE }.take(MAX_PER_CATEGORY),
                uncategorized = all.filter {
                    it.category != ClassificationResult.MATTERS && it.category != ClassificationResult.NOISE
                }.take(MAX_PER_CATEGORY),
                totalCount = all.size
            )
        }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AllNotificationsUiState()
    )
}
