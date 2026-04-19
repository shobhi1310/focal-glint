package com.focal.ui.digest

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import com.focal.intelligence.SuggestedAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

data class TopicDetailUiState(
    val topic: TopicEntity? = null,
    val notifications: List<NotificationEntity> = emptyList(),
    val actions: List<SuggestedAction> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class TopicDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val topicRepository: TopicRepository,
    private val notificationRepository: NotificationRepository
) : ViewModel() {

    private val topicId: String = savedStateHandle.get<String>("topicId") ?: ""

    private val _uiState = MutableStateFlow(TopicDetailUiState())
    val uiState: StateFlow<TopicDetailUiState> = _uiState.asStateFlow()

    init {
        loadTopic()
    }

    private fun loadTopic() {
        viewModelScope.launch {
            try {
                val topic = topicRepository.getById(topicId)
                if (topic != null) {
                    val notificationIds = parseNotificationIds(topic.notificationIds)
                    val notifications = if (notificationIds.isNotEmpty()) {
                        notificationRepository.getByIds(notificationIds)
                    } else {
                        emptyList()
                    }
                    val actions = SuggestedAction.listFromJson(topic.suggestedActions)
                    _uiState.value = TopicDetailUiState(
                        topic = topic,
                        notifications = notifications,
                        actions = actions,
                        isLoading = false
                    )
                } else {
                    _uiState.value = TopicDetailUiState(isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.value = TopicDetailUiState(isLoading = false)
            }
        }
    }

    private fun parseNotificationIds(json: String): List<String> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
