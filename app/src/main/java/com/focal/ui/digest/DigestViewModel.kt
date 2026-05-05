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
import com.focal.worker.InferenceWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events: SharedFlow<String> = _events.asSharedFlow()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<DigestUiState> = combine(
        topicRepository.getStoryTopics(),
        notificationRepository.countByCategory(ClassificationResult.MATTERS),
        notificationRepository.countByCategory(ClassificationResult.NOISE),
        notificationRepository.totalCount(),
        isProcessing
    ) { values ->
        val stories = values[0] as List<TopicEntity>
        val mattersCount = values[1] as Int
        val noiseCount = values[2] as Int
        val totalNotifications = values[3] as Int
        val processing = values[4] as Boolean

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
        val name = com.focal.ui.components.UserPreference.getUserName(context)
        return if (name.isNotBlank()) "$timeOfDay, $name." else "$timeOfDay."
    }

    private fun buildDayTimeLabel(): String {
        val sdf = java.text.SimpleDateFormat("EEEE · h:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date()).uppercase()
    }

    fun onRefresh() {
        viewModelScope.launch {
            val workManager = WorkManager.getInstance(context)
            val current = workManager
                .getWorkInfosForUniqueWorkFlow(InferenceWorker.WORK_NAME)
                .first()
            val alreadyBusy = current.any { !it.state.isFinished }
            if (alreadyBusy) {
                _events.tryEmit("Already curating your digest — try again in a few seconds.")
                return@launch
            }

            isProcessing.value = true
            TopicEngine.pendingFullRebuild.set(true)
            val workRequest = OneTimeWorkRequestBuilder<InferenceWorker>().build()
            workManager.enqueueUniqueWork(
                InferenceWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            workManager.getWorkInfosForUniqueWorkFlow(InferenceWorker.WORK_NAME)
                .collect { workInfos ->
                    if (workInfos.isEmpty() || workInfos.all { it.state.isFinished }) {
                        isProcessing.value = false
                        return@collect
                    }
                }
        }
    }
}
