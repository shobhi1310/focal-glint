package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

private const val TAG = "Classifier"
private const val CLASSIFICATION_SYSTEM =
    "You are a notification classifier. Classify each notification as 'matters' (personally relevant to the user) or 'noise' (generic, promotional, or irrelevant). Call classifyNotification exactly once. No prose."

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
        Log.i(TAG, "classify: app=${notification.appName} promptLen=${prompt.length}")

        val tool = ClassifyNotificationTool()

        return try {
            val rawResponse = StringBuilder()
            var messageCount = 0
            var toolCallCount = 0

            inferenceProvider.generateWithTools(CLASSIFICATION_SYSTEM, prompt, listOf(tool))
                .catch { e ->
                    Log.e(TAG, "stream error: ${e.message}", e)
                    throw e
                }
                .collect { message ->
                    val calls = message.toolCalls
                    calls?.forEachIndexed { i, call ->
                        Log.i(TAG, "toolCall[$i]: name=${call.name} args=${call.arguments}")
                        toolCallCount++
                    }
                    rawResponse.append(message.toString())
                    messageCount++
                }

            val finalText = ThinkingMode.stripThoughtBlocks(rawResponse.toString()).trim()
            Log.i(TAG, "stream done: messages=$messageCount toolCalls=$toolCallCount rawChars=${rawResponse.length} finalChars=${finalText.length}")
            val isSyntheticToolEcho = finalText.startsWith("response:") || finalText.contains("<tool_call|>")
            if (finalText.isNotEmpty() && !isSyntheticToolEcho) {
                Log.w(TAG, "unexpected prose after tool execution (tool may not have fired): '${finalText.take(200)}'")
            }

            val category = when (tool.lastCategory?.lowercase()?.trim()) {
                "matters" -> ClassificationResult.MATTERS
                "noise" -> ClassificationResult.NOISE
                else -> ClassificationResult.UNCATEGORIZED
            }
            Log.i(TAG, "result: category=$category reason=${tool.lastReason}")

            ClassificationResult(
                category = category,
                classifiedBy = "llm",
                reason = tool.lastReason ?: "No reason provided"
            )
        } catch (e: Exception) {
            Log.e(TAG, "LLM inference failed", e)
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
