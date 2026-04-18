package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.NotificationRepository

class Summarizer(
    private val inferenceProvider: InferenceProvider,
    private val notificationRepository: NotificationRepository
) {
    suspend fun summarizeForApp(packageName: String): NotificationEntity? {
        if (!inferenceProvider.isReady()) return null

        val profile = notificationRepository.getAppProfile(packageName) ?: return null
        val notifications = notificationRepository.getNotificationsByPackage(packageName)
            .filter { !it.isSummary && it.category != ClassificationResult.NOISE }

        if (notifications.size < 3) return null

        return when (profile.appType) {
            "messaging" -> summarizeMessaging(profile, notifications)
            "transactional" -> summarizeTransactional(profile, notifications)
            "promotional" -> summarizePromotional(profile, notifications)
            else -> summarizeGeneric(profile, notifications)
        }
    }

    private suspend fun summarizeMessaging(
        profile: AppProfileEntity,
        notifications: List<NotificationEntity>
    ): NotificationEntity? {
        val grouped = notifications.groupBy { it.conversation ?: it.title }
        val summaries = mutableListOf<String>()

        for ((group, notifs) in grouped) {
            if (notifs.size < 2) continue
            val prompt = PromptBuilder.buildSummarizationPrompt(
                appName = profile.appName,
                conversationName = group,
                notifications = notifs.take(20)
            )
            try {
                val response = inferenceProvider.generate(prompt, maxTokens = 100)
                val summary = LlmResponseParser.parseSummary(response)
                if (summary != null) {
                    summaries.add("$group: $summary")
                }
            } catch (e: Exception) {
                Log.w("Summarizer", "Failed to summarize $group", e)
            }
        }

        if (summaries.isEmpty()) return null

        return NotificationEntity(
            packageName = profile.packageName,
            appName = profile.appName,
            title = profile.appName,
            content = "${notifications.size} messages",
            summaryText = summaries.joinToString("\n"),
            postedAt = notifications.maxOf { it.postedAt },
            category = ClassificationResult.INFORMATIONAL,
            classifiedBy = "llm",
            processedAt = System.currentTimeMillis(),
            isSummary = true
        )
    }

    private fun summarizeTransactional(
        profile: AppProfileEntity,
        notifications: List<NotificationEntity>
    ): NotificationEntity? {
        val latest = notifications.maxByOrNull { it.postedAt } ?: return null

        return NotificationEntity(
            packageName = profile.packageName,
            appName = profile.appName,
            title = profile.appName,
            content = "${notifications.size} updates",
            summaryText = latest.bigText ?: latest.content,
            postedAt = latest.postedAt,
            category = latest.category,
            classifiedBy = "llm",
            processedAt = System.currentTimeMillis(),
            isSummary = true
        )
    }

    private fun summarizePromotional(
        profile: AppProfileEntity,
        notifications: List<NotificationEntity>
    ): NotificationEntity {
        return NotificationEntity(
            packageName = profile.packageName,
            appName = profile.appName,
            title = profile.appName,
            content = "${notifications.size} promotional notifications",
            summaryText = "${notifications.size} promotional notifications hidden",
            postedAt = notifications.maxOf { it.postedAt },
            category = ClassificationResult.NOISE,
            classifiedBy = "llm",
            processedAt = System.currentTimeMillis(),
            isSummary = true
        )
    }

    private suspend fun summarizeGeneric(
        profile: AppProfileEntity,
        notifications: List<NotificationEntity>
    ): NotificationEntity? {
        val prompt = PromptBuilder.buildSummarizationPrompt(
            appName = profile.appName,
            conversationName = null,
            notifications = notifications.take(20)
        )
        return try {
            val response = inferenceProvider.generate(prompt, maxTokens = 100)
            val summary = LlmResponseParser.parseSummary(response) ?: return null

            NotificationEntity(
                packageName = profile.packageName,
                appName = profile.appName,
                title = profile.appName,
                content = "${notifications.size} notifications",
                summaryText = summary,
                postedAt = notifications.maxOf { it.postedAt },
                category = ClassificationResult.INFORMATIONAL,
                classifiedBy = "llm",
                processedAt = System.currentTimeMillis(),
                isSummary = true
            )
        } catch (e: Exception) {
            Log.w("Summarizer", "Failed to summarize ${profile.appName}", e)
            null
        }
    }
}
