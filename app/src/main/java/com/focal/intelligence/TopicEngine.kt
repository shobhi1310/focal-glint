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
    private val embeddingProvider: EmbeddingProvider,
    private val notificationRepository: NotificationRepository,
    private val topicRepository: TopicRepository
) {
    companion object {
        private const val TAG = "TopicEngine"

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
                if (!embeddingProvider.isReady()) {
                    pendingFullRebuild.set(true)
                    Log.d(TAG, "Embedding provider not ready; deferring full rebuild until a later worker run")
                    return
                }
            }

            // Phase 1: Embed unembedded notifications
            if (embeddingProvider.isReady()) {
                val unembedded = notificationRepository.getUnembedded(dayStart, dayEnd)
                if (unembedded.isNotEmpty()) {
                    Log.d(TAG, "Embedding ${unembedded.size} notifications")
                    for (notif in unembedded) {
                        try {
                            val request = EmbeddingTextFormatter.buildRequest(notif) ?: continue
                            val vec = embeddingProvider.embed(request)
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
                        Log.d(TAG, "Deferring topic assignment for ${notif.id}: embedding missing")
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
        val assignThreshold = TopicClusteringPolicy.ASSIGN_THRESHOLD
        val activeTopics = topicRepository.getActiveTopicsInWindow(dayStart, dayEnd)
        val embeddingText = EmbeddingTextFormatter.buildRequest(notif)?.combinedText ?: ""

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
                threshold = assignThreshold,
                assigned = false
            )

            if (topicBest > bestScore) {
                bestScore = topicBest
                bestTopicId = topic.id
            }
        }

        val assigned = bestScore >= assignThreshold && bestTopicId != null
        if (bestTopicId != null) {
            DebugLogger.logEmbeddingScore(
                notifId = notif.id,
                notifTitle = notif.title,
                embeddingText = embeddingText,
                topicId = "BEST=$bestTopicId",
                score = bestScore,
                threshold = assignThreshold,
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

    private fun parseJsonArray(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }
}
