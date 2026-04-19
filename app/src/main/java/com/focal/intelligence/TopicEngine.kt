package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import org.json.JSONArray

class TopicEngine(
    private val inferenceProvider: InferenceProvider,
    private val embeddingProvider: EmbeddingProvider,
    private val notificationRepository: NotificationRepository,
    private val topicRepository: TopicRepository
) {
    companion object {
        private const val TAG = "TopicEngine"
        private const val MAX_LLM_CALLS = 8
        private const val ASSIGN_THRESHOLD = 0.60f

        @Volatile
        var pendingFullRebuild = false
    }

    suspend fun generateTopics(fullRebuild: Boolean = pendingFullRebuild.also { pendingFullRebuild = false }) {
        try {
            val (dayStart, dayEnd) = DayWindow.getWindow()

            if (fullRebuild) {
                Log.d(TAG, "Full rebuild requested")
                notificationRepository.resetAllProcessedFlags()
                topicRepository.clearAndSaveTopics(emptyList())
            }

            // Phase 1: Embed unembedded notifications
            if (embeddingProvider.isReady()) {
                val unembedded = notificationRepository.getUnembedded(dayStart, dayEnd)
                if (unembedded.isNotEmpty()) {
                    Log.d(TAG, "Embedding ${unembedded.size} notifications")
                    for (notif in unembedded) {
                        try {
                            val text = buildEmbeddingText(notif)
                            if (text.isBlank()) continue
                            val vec = embeddingProvider.embed(text)
                            notificationRepository.setEmbedding(notif.id, VectorMath.toBytes(vec))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to embed ${notif.id}", e)
                        }
                    }
                }
            }

            // Phase 2: Assign unprocessed notifications to topics
            val unprocessed = notificationRepository.getUnprocessedMatters(dayStart, dayEnd)
            if (unprocessed.isEmpty()) {
                Log.d(TAG, "No unprocessed notifications")
                return
            }

            Log.d(TAG, "Assigning ${unprocessed.size} notifications to topics")
            val processedIds = mutableListOf<String>()

            for (notif in unprocessed) {
                try {
                    val vec = notif.embedding?.let { VectorMath.toFloats(it) }
                    if (vec == null) {
                        processedIds.add(notif.id)
                        continue
                    }
                    assignOrCreateTopic(notif, vec, dayStart, dayEnd)
                    processedIds.add(notif.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to assign ${notif.id}", e)
                }
            }

            if (processedIds.isNotEmpty()) {
                notificationRepository.markProcessedForTopics(processedIds)
            }

            // Phase 3: Regenerate narratives for dirty topics
            regenerateNarratives(dayStart, dayEnd)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate topics", e)
        }
    }

    private suspend fun assignOrCreateTopic(
        notif: NotificationEntity,
        vec: FloatArray,
        dayStart: Long,
        dayEnd: Long
    ) {
        val activeTopics = topicRepository.getActiveTopicsInWindow(dayStart, dayEnd)

        if (activeTopics.isEmpty()) {
            createNewTopic(notif)
            return
        }

        var bestTopicId: String? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (topic in activeTopics) {
            val memberIds = parseJsonArray(topic.notificationIds)
            val members = notificationRepository.getByIds(memberIds)

            var topicBest = Float.NEGATIVE_INFINITY
            for (m in members) {
                val mv = m.embedding?.let { VectorMath.toFloats(it) } ?: continue
                val s = VectorMath.dot(vec, mv)
                if (s > topicBest) topicBest = s
            }

            if (topicBest > bestScore) {
                bestScore = topicBest
                bestTopicId = topic.id
            }
        }

        if (bestScore >= ASSIGN_THRESHOLD && bestTopicId != null) {
            appendToTopic(bestTopicId, notif)
            topicRepository.markDirty(bestTopicId)
            Log.d(TAG, "Assigned ${notif.appName}/${notif.title} to topic $bestTopicId (score=$bestScore)")
        } else {
            createNewTopic(notif)
            Log.d(TAG, "Created new topic for ${notif.appName}/${notif.title} (bestScore=$bestScore)")
        }
    }

    private suspend fun createNewTopic(notif: NotificationEntity) {
        val headline = "${notif.title}: ${notif.content.take(60)}"
        val sourceApps = JSONArray(listOf(notif.appName)).toString()
        val notificationIds = JSONArray(listOf(notif.id)).toString()

        val appType = DetailTemplates.detectAppType(notif.appName, notif.packageName)
        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(listOf(notif), appType)

        val topic = TopicEntity(
            headline = headline,
            summary = (notif.bigText ?: notif.content).take(500),
            category = ClassificationResult.MATTERS,
            notificationIds = notificationIds,
            sourceApps = sourceApps,
            channelCount = 1,
            detailJson = detailJson,
            actionLabel = actionLabel,
            actionPackage = notif.packageName,
            needsNarrativeRegen = true
        )
        topicRepository.saveTopic(topic)
    }

    private suspend fun appendToTopic(topicId: String, notif: NotificationEntity) {
        val topic = topicRepository.getById(topicId) ?: return
        val existingIds = parseJsonArray(topic.notificationIds).toMutableList()
        existingIds.add(notif.id)

        val existingApps = parseJsonArray(topic.sourceApps).toMutableSet()
        existingApps.add(notif.appName)

        topicRepository.updateTopicMembers(
            topicId = topicId,
            notificationIds = JSONArray(existingIds).toString(),
            sourceApps = JSONArray(existingApps.toList()).toString(),
            channelCount = existingApps.size
        )
    }

    private suspend fun regenerateNarratives(dayStart: Long, dayEnd: Long) {
        val allTopicsInWindow = topicRepository.getActiveTopicsInWindow(dayStart, dayEnd)
        val dirtyTopics = allTopicsInWindow.filter { it.needsNarrativeRegen }

        if (dirtyTopics.isEmpty()) return

        var llmCallCount = 0
        val allNarratives = mutableListOf<String>()
        val noiseCount = notificationRepository.getRecentNotificationsSnapshot()
            .count { !it.isSummary && it.category == ClassificationResult.NOISE }

        for (topic in dirtyTopics) {
            val memberIds = parseJsonArray(topic.notificationIds)
            val members = notificationRepository.getByIds(memberIds)

            val headline: String
            var isLlmGenerated = false

            if (members.size == 1) {
                val m = members.first()
                headline = "${m.title}: ${m.content.take(60)}"
                isLlmGenerated = true
            } else if (inferenceProvider.isReady() && llmCallCount < MAX_LLM_CALLS) {
                headline = try {
                    val prompt = PromptBuilder.buildNarrativePrompt(members)
                    val raw = inferenceProvider.generate(prompt, maxTokens = 128)
                    llmCallCount++
                    val parsed = LlmResponseParser.parseNarrative(raw)
                    isLlmGenerated = parsed != null
                    parsed ?: "${members.first().appName} · ${members.size} messages"
                } catch (e: Exception) {
                    Log.w(TAG, "Narrative generation failed for topic ${topic.id}", e)
                    "${members.first().appName} · ${members.size} messages"
                }
            } else {
                headline = "${members.first().appName} · ${members.size} messages"
            }

            topicRepository.updateTopicHeadline(
                topicId = topic.id,
                headline = headline,
                summary = members.joinToString(". ") { (it.bigText ?: it.content).take(100) }.take(500),
                briefingContribution = if (isLlmGenerated) headline else null
            )
            topicRepository.markClean(topic.id)

            if (isLlmGenerated) allNarratives.add(headline)
        }

        // Also collect narratives from clean (non-dirty) topics for briefing completeness
        allTopicsInWindow.filter { !it.needsNarrativeRegen }.forEach {
            it.briefingContribution?.let { bc -> allNarratives.add(bc) }
        }

        // Generate briefing
        if (inferenceProvider.isReady() && allNarratives.isNotEmpty() && llmCallCount < MAX_LLM_CALLS) {
            try {
                val briefingPrompt = PromptBuilder.buildBriefingPrompt(allNarratives, noiseCount)
                val briefingRaw = inferenceProvider.generate(briefingPrompt, maxTokens = 256)
                val briefingText = briefingRaw.trim().ifBlank { null }

                if (briefingText != null) {
                    val existingBriefing = topicRepository.getBriefingInWindow(dayStart, dayEnd)
                    if (existingBriefing != null) {
                        topicRepository.updateTopicHeadline(
                            topicId = existingBriefing.id,
                            headline = "BRIEFING",
                            summary = briefingText.take(500),
                            briefingContribution = null
                        )
                    } else {
                        topicRepository.saveTopic(TopicEntity(
                            headline = "BRIEFING",
                            summary = briefingText.take(500),
                            category = ClassificationResult.MATTERS,
                            notificationIds = "[]",
                            sourceApps = "[]"
                        ))
                    }
                    Log.d(TAG, "Generated daily briefing")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to generate briefing", e)
            }
        }
    }

    private fun buildEmbeddingText(notif: NotificationEntity): String {
        val content = notif.bigText ?: notif.content
        if (notif.title.isBlank() && content.isBlank()) return ""
        return "${notif.appName} — ${notif.title}: ${content.take(300)}"
    }

    private fun parseJsonArray(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }
}
