package com.focal.ui.digest

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
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
    val briefing: String? = null,
    val stories: List<TopicEntity> = emptyList(),
    val noiseCount: Int = 0,
    val totalNotifications: Int = 0,
    val isProcessing: Boolean = false
)

@HiltViewModel
class DigestViewModel @Inject constructor(
    private val topicRepository: TopicRepository,
    private val notificationRepository: NotificationRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val isProcessing = MutableStateFlow(false)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<DigestUiState> = combine(
        topicRepository.getStoryTopics(),
        topicRepository.getBriefing(),
        notificationRepository.countByCategory(ClassificationResult.NOISE),
        notificationRepository.totalCount(),
        isProcessing
    ) { values ->
        val stories = values[0] as List<TopicEntity>
        val briefingTopic = values[1] as TopicEntity?
        val noiseCount = values[2] as Int
        val totalNotifications = values[3] as Int
        val processing = values[4] as Boolean

        DigestUiState(
            briefing = briefingTopic?.summary,
            stories = stories,
            noiseCount = noiseCount,
            totalNotifications = totalNotifications,
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
