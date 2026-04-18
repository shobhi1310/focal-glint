package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity

class Classifier(
    private val inferenceProvider: InferenceProvider
) {
    suspend fun classify(notification: NotificationEntity): ClassificationResult {
        if (!inferenceProvider.isReady()) {
            return ClassificationResult(
                category = ClassificationResult.UNCATEGORIZED,
                classifiedBy = "pending"
            )
        }

        val prompt = PromptBuilder.buildClassificationPrompt(notification)

        return try {
            val response = inferenceProvider.generate(prompt, maxTokens = 100)
            LlmResponseParser.parseMattersClassification(response)
                ?: ClassificationResult(
                    category = ClassificationResult.UNCATEGORIZED,
                    classifiedBy = "llm",
                    reason = "Failed to parse"
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
