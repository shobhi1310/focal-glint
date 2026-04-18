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
    private val topicRepository: TopicRepository,
    private val summarizer: Summarizer
) {
    companion object {
        private const val TAG = "TopicEngine"
    }

    suspend fun generateTopics() {
        try {
            val notifications = notificationRepository.getRecentNotificationsSnapshot()
            if (notifications.isEmpty()) {
                Log.d(TAG, "No recent notifications, skipping topic generation")
                return
            }

            val grouped = notifications.groupBy { it.packageName }
            Log.d(TAG, "Found ${grouped.size} app groups from ${notifications.size} notifications")

            // Build initial app-level topics
            val appTopics = grouped.map { (packageName, notifs) ->
                buildAppTopic(packageName, notifs)
            }

            // Attempt LLM merge if ready and multiple groups
            val mergedTopics = if (inferenceProvider.isReady() && appTopics.size > 1) {
                tryMergeTopics(appTopics)
            } else {
                appTopics.map { listOf(it) }
            }

            // Build final TopicEntity list
            val topics = mergedTopics.mapNotNull { group ->
                buildTopicEntity(group)
            }

            if (topics.isNotEmpty()) {
                topicRepository.clearAndSaveTopics(topics)
                Log.d(TAG, "Saved ${topics.size} topics")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate topics", e)
        }
    }

    private suspend fun buildAppTopic(
        packageName: String,
        notifications: List<NotificationEntity>
    ): AppTopic {
        val appName = notifications.first().appName
        val summary = try {
            val summaryEntity = summarizer.summarizeForApp(packageName)
            summaryEntity?.summaryText ?: notifications.first().content.take(100)
        } catch (e: Exception) {
            Log.w(TAG, "Summarization failed for $packageName", e)
            notifications.first().content.take(100)
        }

        // Determine dominant category
        val categoryVotes = notifications.groupBy { it.category }
        val dominantCategory = categoryVotes.maxByOrNull { it.value.size }?.key
            ?: ClassificationResult.UNCATEGORIZED

        return AppTopic(
            appName = appName,
            packageName = packageName,
            summary = summary,
            category = dominantCategory,
            notifications = notifications
        )
    }

    private suspend fun tryMergeTopics(appTopics: List<AppTopic>): List<List<AppTopic>> {
        try {
            val prompt = buildMergePrompt(appTopics)
            val response = inferenceProvider.generate(prompt, maxTokens = 200)
            return parseMergeResponse(response, appTopics)
        } catch (e: Exception) {
            Log.w(TAG, "LLM merge failed, keeping groups separate", e)
            return appTopics.map { listOf(it) }
        }
    }

    private fun buildMergePrompt(appTopics: List<AppTopic>): String {
        val sb = StringBuilder()
        sb.appendLine("Here are notification groups from the last 24 hours:")

        appTopics.forEachIndexed { index, topic ->
            sb.appendLine("${index + 1}. ${topic.appName}: ${topic.summary.take(80)}")
        }

        sb.appendLine()
        sb.appendLine("Which groups are about the same topic? Respond as JSON array of arrays:")
        sb.appendLine("[[1,2],[3],[4,5]]")

        return sb.toString()
    }

    private fun parseMergeResponse(
        response: String,
        appTopics: List<AppTopic>
    ): List<List<AppTopic>> {
        try {
            val jsonStr = extractJsonArray(response) ?: return appTopics.map { listOf(it) }
            val outerArray = JSONArray(jsonStr)
            val merged = mutableListOf<List<AppTopic>>()
            val used = mutableSetOf<Int>()

            for (i in 0 until outerArray.length()) {
                val innerArray = outerArray.getJSONArray(i)
                val group = mutableListOf<AppTopic>()
                for (j in 0 until innerArray.length()) {
                    val index = innerArray.getInt(j) - 1 // 1-based to 0-based
                    if (index in appTopics.indices && index !in used) {
                        group.add(appTopics[index])
                        used.add(index)
                    }
                }
                if (group.isNotEmpty()) {
                    merged.add(group)
                }
            }

            // Add any topics not included in merge response
            appTopics.forEachIndexed { index, topic ->
                if (index !in used) {
                    merged.add(listOf(topic))
                }
            }

            return merged
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse merge response: ${response.take(200)}", e)
            return appTopics.map { listOf(it) }
        }
    }

    private fun extractJsonArray(raw: String): String? {
        val trimmed = raw.trim()

        // Direct array
        if (trimmed.startsWith("[")) {
            val depth = IntArray(1)
            for (i in trimmed.indices) {
                when (trimmed[i]) {
                    '[' -> depth[0]++
                    ']' -> {
                        depth[0]--
                        if (depth[0] == 0) return trimmed.substring(0, i + 1)
                    }
                }
            }
        }

        // Find array in text
        val pattern = Regex("\\[\\s*\\[.*?]\\s*]", RegexOption.DOT_MATCHES_ALL)
        pattern.find(trimmed)?.let { return it.value }

        return null
    }

    private fun buildTopicEntity(group: List<AppTopic>): TopicEntity? {
        if (group.isEmpty()) return null

        val allNotifications = group.flatMap { it.notifications }
        val allNotificationIds = allNotifications.map { it.id }
        val sourceApps = group.map { it.packageName }.distinct()

        val headline = if (group.size == 1) {
            group.first().appName
        } else {
            group.joinToString(" + ") { it.appName }
        }

        val summary = group.joinToString(". ") { it.summary }

        // Use highest-priority category from merged groups
        val categoryPriority = listOf(
            ClassificationResult.URGENT,
            ClassificationResult.ACTIONABLE,
            ClassificationResult.DIGEST,
            ClassificationResult.NOISE,
            ClassificationResult.UNCATEGORIZED
        )
        val category = group.map { it.category }
            .minByOrNull { categoryPriority.indexOf(it).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
            ?: ClassificationResult.UNCATEGORIZED

        // Try detail extraction
        val primaryApp = group.maxByOrNull { it.notifications.size } ?: group.first()
        val appType = DetailTemplates.detectAppType(primaryApp.appName, primaryApp.packageName)
        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(allNotifications, appType)

        // Build JSON arrays for notificationIds and sourceApps
        val notificationIdsJson = JSONArray(allNotificationIds).toString()
        val sourceAppsJson = JSONArray(sourceApps).toString()

        return TopicEntity(
            headline = headline,
            summary = summary.take(500),
            category = category,
            notificationIds = notificationIdsJson,
            sourceApps = sourceAppsJson,
            channelCount = sourceApps.size,
            detailJson = detailJson,
            actionLabel = actionLabel,
            actionPackage = primaryApp.packageName
        )
    }

    private data class AppTopic(
        val appName: String,
        val packageName: String,
        val summary: String,
        val category: String,
        val notifications: List<NotificationEntity>
    )
}
