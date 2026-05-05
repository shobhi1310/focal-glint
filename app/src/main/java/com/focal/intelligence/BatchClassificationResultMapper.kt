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
        val resolved = when (category.lowercase().trim()) {
            "matters" -> ClassificationResult.MATTERS
            "noise" -> ClassificationResult.NOISE
            else -> return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
        }
        notification to ClassificationResult(category = resolved, classifiedBy = "llm", reason = reason)
    }

    fun mattersIndices(results: List<Pair<NotificationEntity, ClassificationResult>>): List<Int> =
        results.mapIndexedNotNull { i, (_, result) ->
            if (result.category == ClassificationResult.MATTERS) i + 1 else null
        }
}
