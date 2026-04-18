package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import org.json.JSONArray

class TopicEngine(
    private val inferenceProvider: InferenceProvider,
    private val notificationRepository: NotificationRepository,
    private val topicRepository: TopicRepository
) {
    companion object {
        private const val TAG = "TopicEngine"
        private const val MAX_LLM_CALLS = 8
    }

    suspend fun generateTopics() {
        try {
            val allNotifications = notificationRepository.getRecentNotificationsSnapshot()

            val mattersNotifications = allNotifications
                .filter {
                    !it.isSummary &&
                        it.category == ClassificationResult.MATTERS
                }

            if (mattersNotifications.isEmpty()) {
                Log.d(TAG, "No recent MATTERS notifications, skipping topic generation")
                return
            }

            val noiseCount = allNotifications.count {
                !it.isSummary && it.category == ClassificationResult.NOISE
            }

            Log.d(TAG, "Processing ${mattersNotifications.size} MATTERS notifications, $noiseCount noise hidden")

            // Group by app (packageName)
            val appGroups = mattersNotifications.groupBy { it.packageName }

            // Sort by notification count descending, take top 10 to avoid OOM on low-memory devices
            val topAppGroups = appGroups.entries
                .sortedByDescending { it.value.size }
                .take(10)

            var llmCallCount = 0
            val storyTopics = mutableListOf<TopicEntity>()
            val allNarratives = mutableListOf<String>()

            for ((packageName, appNotifications) in topAppGroups) {
                // Sub-group by conversation/sender
                val senderGroups = appNotifications.groupBy { notif ->
                    notif.conversation ?: notif.title
                }

                for ((_, senderNotifications) in senderGroups) {
                    try {
                        val topic = buildStoryTopic(
                            senderNotifications,
                            llmCallCount
                        ) { llmCallCount++ }

                        storyTopics.add(topic)
                        topic.briefingContribution?.let { allNarratives.add(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to build topic for sender group in $packageName, skipping", e)
                    }
                }
            }

            // Generate daily briefing
            val allTopics = mutableListOf<TopicEntity>()
            allTopics.addAll(storyTopics)

            if (inferenceProvider.isReady() && allNarratives.isNotEmpty() && llmCallCount < MAX_LLM_CALLS) {
                try {
                    val briefingPrompt = PromptBuilder.buildBriefingPrompt(allNarratives, noiseCount)
                    val briefingRaw = inferenceProvider.generate(briefingPrompt, maxTokens = 256)
                    val briefingText = briefingRaw.trim().ifBlank { null }

                    if (briefingText != null) {
                        val briefingTopic = TopicEntity(
                            headline = "BRIEFING",
                            summary = briefingText.take(500),
                            category = ClassificationResult.MATTERS,
                            notificationIds = "[]",
                            sourceApps = "[]"
                        )
                        allTopics.add(briefingTopic)
                        Log.d(TAG, "Generated daily briefing")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to generate briefing, skipping", e)
                }
            }

            if (allTopics.isNotEmpty()) {
                topicRepository.clearAndSaveTopics(allTopics)
                Log.d(TAG, "Saved ${allTopics.size} topics (${storyTopics.size} stories)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate topics", e)
        }
    }

    private suspend fun buildStoryTopic(
        notifications: List<NotificationEntity>,
        currentLlmCallCount: Int,
        onLlmCall: () -> Unit
    ): TopicEntity {
        val appName = notifications.first().appName
        val packageName = notifications.first().packageName

        val headline: String
        if (notifications.size == 1) {
            // Single notification — use title: content snippet, no LLM needed
            val notif = notifications.first()
            headline = "${notif.title}: ${notif.content.take(60)}"
        } else if (inferenceProvider.isReady() && currentLlmCallCount < MAX_LLM_CALLS) {
            // Multiple notifications with LLM available — generate narrative
            headline = try {
                val prompt = PromptBuilder.buildNarrativePrompt(notifications)
                val raw = inferenceProvider.generate(prompt, maxTokens = 128)
                onLlmCall()
                LlmResponseParser.parseNarrative(raw) ?: "$appName \u00b7 ${notifications.size} messages"
            } catch (e: Exception) {
                Log.w(TAG, "LLM narrative failed for $appName, using template", e)
                "$appName \u00b7 ${notifications.size} messages"
            }
        } else {
            // Fallback template
            headline = "$appName \u00b7 ${notifications.size} messages"
        }

        // Detail extraction
        val appType = DetailTemplates.detectAppType(appName, packageName)
        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(notifications, appType)

        // Build notification IDs and source apps
        val notificationIds = notifications.map { it.id }
        val notificationIdsJson = JSONArray(notificationIds).toString()
        val sourceAppsJson = JSONArray(listOf(appName)).toString()

        return TopicEntity(
            headline = headline,
            summary = notifications.joinToString(". ") {
                (it.bigText ?: it.content).take(100)
            }.take(500),
            category = ClassificationResult.MATTERS,
            notificationIds = notificationIdsJson,
            sourceApps = sourceAppsJson,
            channelCount = 1,
            detailJson = detailJson,
            actionLabel = actionLabel,
            actionPackage = packageName,
            briefingContribution = headline
        )
    }
}
