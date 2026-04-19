package com.focal.ui.digest

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import com.focal.intelligence.ClassificationResult
import com.focal.intelligence.TopicEngine
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
    val greeting: String = "",
    val dayTimeLabel: String = "",
    val stories: List<TopicEntity> = emptyList(),
    val mattersCount: Int = 0,
    val noiseCount: Int = 0,
    val totalNotifications: Int = 0,
    val isProcessing: Boolean = false
)

@HiltViewModel
class DigestViewModel @Inject constructor(
    private val topicRepository: TopicRepository,
    private val notificationRepository: NotificationRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val isProcessing = MutableStateFlow(false)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<DigestUiState> = combine(
        topicRepository.getStoryTopics(),
        topicRepository.getBriefing(),
        notificationRepository.countByCategory(ClassificationResult.MATTERS),
        notificationRepository.countByCategory(ClassificationResult.NOISE),
        notificationRepository.totalCount(),
        isProcessing
    ) { values ->
        val stories = values[0] as List<TopicEntity>
        val mattersCount = values[2] as Int
        val noiseCount = values[3] as Int
        val totalNotifications = values[4] as Int
        val processing = values[5] as Boolean

        DigestUiState(
            greeting = buildGreeting(),
            dayTimeLabel = buildDayTimeLabel(),
            stories = stories,
            mattersCount = mattersCount,
            noiseCount = noiseCount,
            totalNotifications = totalNotifications,
            isProcessing = processing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DigestUiState()
    )

    private fun buildGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when {
            hour in 5..11 -> "Good morning"
            hour in 12..16 -> "Good afternoon"
            hour in 17..20 -> "Good evening"
            else -> "Good night"
        }
        return "$timeOfDay, Shubhankar."
    }

    private fun buildDayTimeLabel(): String {
        val sdf = java.text.SimpleDateFormat("EEEE · h:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date()).uppercase()
    }

    fun onRefresh() {
        viewModelScope.launch {
            isProcessing.value = true
            TopicEngine.pendingFullRebuild = true
            val workManager = WorkManager.getInstance(context)
            val workRequest = OneTimeWorkRequestBuilder<ClassificationWorker>().build()
            workManager.enqueueUniqueWork(
                ClassificationWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            workManager.getWorkInfosForUniqueWorkFlow(ClassificationWorker.WORK_NAME)
                .collect { workInfos ->
                    if (workInfos.isEmpty() || workInfos.all { it.state.isFinished }) {
                        isProcessing.value = false
                        return@collect
                    }
                }
        }
    }
}
