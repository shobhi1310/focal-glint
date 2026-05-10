package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity

internal object BatchClassificationResultMapper {
    fun missingIndices(
        classifyTool: BatchClassifyNotificationTool,
        notificationCount: Int
    ): List<Int> = (1..notificationCount).filter { classifyTool.getResult(it) == null }

    fun mapResults(
        notifications: List<NotificationEntity>,
        classifyTool: BatchClassifyNotificationTool
    ): List<Pair<NotificationEntity, ClassificationResult>> = notifications.mapIndexed { i, notification ->
        val (category, reason) = classifyTool.getResult(i + 1)
            ?: return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
        val isNoise = category.lowercase().trim() in setOf("noise", "promo", "promotional", "spam", "irrelevant")
        val resolved = if (isNoise) ClassificationResult.NOISE else ClassificationResult.MATTERS
        notification to ClassificationResult(category = resolved, classifiedBy = "llm", reason = reason)
    }

    fun mattersIndices(results: List<Pair<NotificationEntity, ClassificationResult>>): List<Int> =
        results.mapIndexedNotNull { i, (_, result) ->
            if (result.category == ClassificationResult.MATTERS) i + 1 else null
        }
}
