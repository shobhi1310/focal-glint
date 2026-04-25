package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopicNarrativeProcessor @Inject constructor(
    private val inferenceProvider: InferenceProvider,
    private val notificationRepository: NotificationRepository,
    private val topicRepository: TopicRepository
) {

    suspend fun processDirtyTopics(): Boolean {
        val (dayStart, dayEnd) = DayWindow.getWindow()
        val allTopicsInWindow = topicRepository.getActiveTopicsInWindow(dayStart, dayEnd)
        val dirtyTopics = allTopicsInWindow
            .filter { it.needsNarrativeRegen }
            .sortedWith(compareByDescending<TopicEntity> { parseJsonArray(it.notificationIds).size > 1 }
                .thenByDescending { parseJsonArray(it.notificationIds).size }
                .thenByDescending { it.updatedAt })

        Log.d(
            TAG,
            "Narrative regen: totalTopics=${allTopicsInWindow.size} dirtyTopics=${dirtyTopics.size} llmReady=${inferenceProvider.isReady()}"
        )

        if (dirtyTopics.isEmpty()) {
            Log.d(TAG, "Narrative regen skipped: no dirty topics")
            return false
        }

        for (topic in dirtyTopics) {
            val usedLlm = processTopic(topic)
            if (usedLlm) return hasMoreDirtyTopics(dayStart, dayEnd)
        }

        return hasMoreDirtyTopics(dayStart, dayEnd)
    }

    private suspend fun hasMoreDirtyTopics(dayStart: Long, dayEnd: Long): Boolean {
        return topicRepository.getActiveTopicsInWindow(dayStart, dayEnd).any { it.needsNarrativeRegen }
    }

    private suspend fun processTopic(topic: TopicEntity): Boolean {
        val memberIds = parseJsonArray(topic.notificationIds)
        val members = notificationRepository.getByIds(memberIds)
        Log.d(TAG, "Narrative topic: id=${topic.id} members=${members.size} dirty=${topic.needsNarrativeRegen}")

        if (members.isEmpty()) {
            topicRepository.markClean(topic.id)
            Log.d(TAG, "Narrative clean: topic=${topic.id} reason=no_members")
            return false
        }

        var headline: String
        var summary: String
        var isLlmGenerated = false
        var actions: List<SuggestedAction> = emptyList()
        var usedLlm = false

        if (members.size == 1) {
            val m = members.first()
            headline = m.title.take(40)
            summary = (m.bigText ?: m.content).take(300)
            isLlmGenerated = false
            actions = listOf(
                SuggestedAction(
                    label = "Open in ${m.appName}",
                    type = "open_app",
                    app = m.appName,
                    packageName = m.packageName
                )
            )
            Log.d(TAG, "Narrative fallback: topic=${topic.id} reason=single_member")
        } else if (inferenceProvider.isReady()) {
            usedLlm = true
            headline = try {
                val startMs = System.currentTimeMillis()
                Log.d(TAG, "Narrative LLM start: topic=${topic.id} members=${members.size}")
                val prompt = PromptBuilder.buildTopicPrompt(members)
                val raw = inferenceProvider.generate(prompt, maxTokens = 200)
                val parsed = LlmResponseParser.parseTopicContent(raw)
                if (parsed != null) {
                    isLlmGenerated = true
                    summary = parsed.summary
                    actions = resolveActionPackages(parsed.actions, members)
                    Log.d(
                        TAG,
                        "Narrative LLM parsed: topic=${topic.id} elapsedMs=${System.currentTimeMillis() - startMs} title=${parsed.title.take(60)} actions=${actions.size}"
                    )
                    parsed.title
                } else {
                    Log.d(
                        TAG,
                        "Narrative fallback: topic=${topic.id} reason=parse_failed elapsedMs=${System.currentTimeMillis() - startMs} raw=${raw.take(120)}"
                    )
                    summary = fallbackSummary(members)
                    "${members.first().appName} · ${members.size} messages"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Topic generation failed for topic ${topic.id}", e)
                Log.d(TAG, "Narrative fallback: topic=${topic.id} reason=llm_error error=${e.javaClass.simpleName}")
                summary = fallbackSummary(members)
                "${members.first().appName} · ${members.size} messages"
            }
        } else {
            Log.d(TAG, "Narrative fallback: topic=${topic.id} reason=llm_not_ready members=${members.size}")
            return false
        }

        topicRepository.updateTopicHeadline(
            topicId = topic.id,
            headline = headline,
            summary = summary,
            briefingContribution = null
        )
        if (actions.isNotEmpty()) {
            topicRepository.updateTopicActions(topic.id, SuggestedAction.listToJson(actions))
        }
        topicRepository.markClean(topic.id)
        Log.d(TAG, "Narrative clean: topic=${topic.id} llmGenerated=$isLlmGenerated actions=${actions.size}")
        return usedLlm
    }

    private fun fallbackSummary(members: List<NotificationEntity>): String {
        return members.joinToString(". ") { (it.bigText ?: it.content).take(100) }.take(300)
    }

    private fun resolveActionPackages(
        actions: List<SuggestedAction>,
        members: List<NotificationEntity>
    ): List<SuggestedAction> {
        val knownApps = mapOf(
            "phone" to "com.android.phone",
            "messages" to "com.google.android.apps.messaging",
            "chrome" to "com.android.chrome"
        )
        return actions.map { action ->
            val packageName = members.firstOrNull {
                it.appName.equals(action.app, ignoreCase = true)
            }?.packageName
                ?: knownApps[action.app.lowercase()]
                ?: ""
            action.copy(packageName = packageName)
        }
    }

    private fun parseJsonArray(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val TAG = "TopicEngine"
    }
}
