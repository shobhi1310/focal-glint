package com.focal.intelligence

import android.util.Log
import com.focal.DebugLogger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
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

        val pendingFullRebuild = AtomicBoolean(false)
    }

    suspend fun generateTopics(fullRebuild: Boolean = pendingFullRebuild.getAndSet(false)) {
        try {
            val (dayStart, dayEnd) = DayWindow.getWindow()

            if (fullRebuild) {
                Log.d(TAG, "Full rebuild requested")
                notificationRepository.resetAllEmbeddings()
                notificationRepository.resetAllProcessedFlags()
                notificationRepository.resetUncategorizedForReclassification()
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
                        } catch (e: CancellationException) {
                            throw e
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to assign ${notif.id}", e)
                }
            }

            if (processedIds.isNotEmpty()) {
                notificationRepository.markProcessedForTopics(processedIds)
            }

            // Phase 3: Regenerate narratives for dirty topics
            regenerateNarratives(dayStart, dayEnd)

        } catch (e: CancellationException) {
            throw e
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
        val embeddingText = buildEmbeddingText(notif)

        if (activeTopics.isEmpty()) {
            createNewTopic(notif)
            return
        }

        // Channel-first: same notification slot → same topic, no cosine needed
        if (notif.notificationKey != null) {
            for (topic in activeTopics) {
                val members = notificationRepository.getByIds(parseJsonArray(topic.notificationIds))
                if (members.any { it.notificationKey == notif.notificationKey }) {
                    appendToTopic(topic.id, notif)
                    topicRepository.markDirty(topic.id)
                    Log.d(TAG, "Channel-matched ${notif.appName}/${notif.title} to topic ${topic.id}")
                    return
                }
            }
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

            DebugLogger.logEmbeddingScore(
                notifId = notif.id,
                notifTitle = notif.title,
                embeddingText = embeddingText,
                topicId = topic.id,
                score = topicBest,
                threshold = TopicClusteringPolicy.ASSIGN_THRESHOLD,
                assigned = false
            )

            if (topicBest > bestScore) {
                bestScore = topicBest
                bestTopicId = topic.id
            }
        }

        val assigned = bestScore >= TopicClusteringPolicy.ASSIGN_THRESHOLD && bestTopicId != null
        if (bestTopicId != null) {
            DebugLogger.logEmbeddingScore(
                notifId = notif.id,
                notifTitle = notif.title,
                embeddingText = embeddingText,
                topicId = "BEST=$bestTopicId",
                score = bestScore,
                threshold = TopicClusteringPolicy.ASSIGN_THRESHOLD,
                assigned = assigned
            )
        }

        if (assigned) {
            appendToTopic(bestTopicId!!, notif)
            topicRepository.markDirty(bestTopicId)
            Log.d(TAG, "Assigned ${notif.appName}/${notif.title} to topic $bestTopicId (score=$bestScore)")
        } else {
            createNewTopic(notif)
            Log.d(TAG, "Created new topic for ${notif.appName}/${notif.title} (bestScore=$bestScore)")
        }
    }

    private suspend fun createNewTopic(notif: NotificationEntity) {
        val headline = notif.title.take(40)
        val summary = (notif.bigText ?: notif.content).take(300)
        val sourceApps = JSONArray(listOf(notif.appName)).toString()
        val notificationIds = JSONArray(listOf(notif.id)).toString()

        val appType = DetailTemplates.detectAppType(notif.appName, notif.packageName)
        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(listOf(notif), appType)

        val topic = TopicEntity(
            headline = headline,
            summary = summary,
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

        for (topic in dirtyTopics) {
            val memberIds = parseJsonArray(topic.notificationIds)
            val members = notificationRepository.getByIds(memberIds)

            var headline: String
            var summary: String
            var isLlmGenerated = false
            var actions: List<SuggestedAction> = emptyList()
            val llmSkipped = members.size > 1 && (!inferenceProvider.isReady() || llmCallCount >= MAX_LLM_CALLS)

            if (members.size == 1) {
                val m = members.first()
                headline = m.title.take(40)
                summary = (m.bigText ?: m.content).take(300)
                isLlmGenerated = true
                actions = listOf(SuggestedAction(
                    label = "Open in ${m.appName}",
                    type = "open_app",
                    app = m.appName,
                    packageName = m.packageName
                ))
            } else if (inferenceProvider.isReady() && llmCallCount < MAX_LLM_CALLS) {
                headline = try {
                    val prompt = PromptBuilder.buildTopicPrompt(members)
                    val raw = inferenceProvider.generate(prompt, maxTokens = 200)
                    llmCallCount++
                    val parsed = LlmResponseParser.parseTopicContent(raw)
                    if (parsed != null) {
                        isLlmGenerated = true
                        summary = parsed.summary
                        actions = resolveActionPackages(parsed.actions, members)
                        parsed.title
                    } else {
                        summary = members.joinToString(". ") { (it.bigText ?: it.content).take(100) }.take(300)
                        "${members.first().appName} · ${members.size} messages"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Topic generation failed for topic ${topic.id}", e)
                    summary = members.joinToString(". ") { (it.bigText ?: it.content).take(100) }.take(300)
                    "${members.first().appName} · ${members.size} messages"
                }
            } else {
                headline = "${members.first().appName} · ${members.size} messages"
                summary = members.joinToString(". ") { (it.bigText ?: it.content).take(100) }.take(300)
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
            if (!llmSkipped) topicRepository.markClean(topic.id)
        }
    }

    private fun buildEmbeddingText(notif: NotificationEntity): String {
        if (notif.title.isBlank()) return ""
        val channelId = notif.notificationKey?.split("|")?.getOrNull(2)
            ?.takeIf { it.isNotBlank() && it != "null" }
        val appPrefix = if (channelId != null) "${notif.appName}/$channelId" else notif.appName
        val body = when {
            notif.extrasJson != null -> extractRecentThreadText(notif.extrasJson)
            notif.bigText != null    -> buildEmailBody(notif.content, notif.bigText)
            else                     -> notif.content.takeIf { it.isNotBlank() }
        } ?: return ""
        return "$appPrefix — ${notif.title}: ${body.take(600)}"
    }

    private fun extractRecentThreadText(extrasJson: String?): String? {
        if (extrasJson == null) return null
        return try {
            val arr = JSONArray(extrasJson)
            val texts = (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
                .distinct()
                .takeLast(3)
            if (texts.isEmpty()) null else texts.joinToString(" | ")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse extrasJson for embedding", e)
            null
        }
    }

    private fun buildEmailBody(content: String, bigText: String): String {
        val preview = bigText.take(500)
        // bigText usually starts with the subject — avoid duplicating it
        return if (preview.startsWith(content.take(50))) preview
               else "$content\n$preview"
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
        } catch (_: Exception) { emptyList() }
    }
}
