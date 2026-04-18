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
    val matters: List<NotificationEntity> = emptyList(),
    val noise: List<NotificationEntity> = emptyList(),
    val totalCount: Int = 0
)

@HiltViewModel
class AllNotificationsViewModel @Inject constructor(
    notificationRepository: NotificationRepository
) : ViewModel() {

    companion object {
        private const val MAX_PER_CATEGORY = 20
    }

    private val categoriesFlow = combine(
        notificationRepository.getByCategory(ClassificationResult.MATTERS),
        notificationRepository.getByCategory(ClassificationResult.NOISE)
    ) { matters, noise ->
        listOf(matters, noise)
    }

    val uiState: StateFlow<AllNotificationsUiState> = combine(
        categoriesFlow,
        notificationRepository.totalCount()
    ) { categories, total ->
        AllNotificationsUiState(
            matters = categories[0].take(MAX_PER_CATEGORY),
            noise = categories[1].take(MAX_PER_CATEGORY),
            totalCount = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AllNotificationsUiState()
    )
}
