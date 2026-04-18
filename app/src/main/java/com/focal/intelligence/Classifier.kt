package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository

class Classifier(
    private val inferenceProvider: InferenceProvider,
    private val notificationRepository: NotificationRepository,
    private val ruleRepository: RuleRepository
) {
    suspend fun classify(notification: NotificationEntity): ClassificationResult {
        if (!inferenceProvider.isReady()) {
            Log.w("Classifier", "LLM not ready, returning uncategorized")
            return ClassificationResult(
                category = ClassificationResult.UNCATEGORIZED,
                classifiedBy = "pending"
            )
        }

        val appProfile = notificationRepository.getAppProfile(notification.packageName)
        val recentCorrections = ruleRepository.getRecentCorrections(5)
        // For v1, correction notification lookup is empty — PromptBuilder handles missing entries gracefully
        val correctionNotifications = emptyMap<String, NotificationEntity>()

        val prompt = PromptBuilder.buildClassificationPrompt(
            notification = notification,
            appProfile = appProfile,
            recentCorrections = recentCorrections,
            correctionNotifications = correctionNotifications
        )

        return try {
            val response = inferenceProvider.generate(prompt, maxTokens = 128)
            LlmResponseParser.parseClassification(response)
                ?: ClassificationResult(
                    category = ClassificationResult.UNCATEGORIZED,
                    classifiedBy = "llm",
                    reason = "Failed to parse LLM response"
                )
        } catch (e: Exception) {
            Log.e("Classifier", "LLM inference failed", e)
            ClassificationResult(
                category = ClassificationResult.UNCATEGORIZED,
                classifiedBy = "pending",
                reason = "LLM error: ${e.message}"
            )
        }
    }

    suspend fun classifyBatch(notifications: List<NotificationEntity>): List<Pair<NotificationEntity, ClassificationResult>> {
        return notifications.map { notification ->
            val result = classify(notification)
            notification to result
        }
    }
}
