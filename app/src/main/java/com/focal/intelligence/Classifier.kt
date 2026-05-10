package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.WidgetRepository
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

private const val TAG = "Classifier"
private val TOOL_ECHO_PREFIX = Regex("""^classifyNotification\s*[\(\{]""")

internal fun isSyntheticToolEcho(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("response:") ||
        trimmed.contains("<tool_call|>") ||
        TOOL_ECHO_PREFIX.containsMatchIn(trimmed)
}

internal fun wasToolExecuted(tool: ClassifyNotificationTool): Boolean =
    !tool.lastCategory.isNullOrBlank()

internal fun shouldWarnAboutUnexpectedProse(finalText: String, toolExecuted: Boolean): Boolean =
    finalText.isNotEmpty() && !toolExecuted && !isSyntheticToolEcho(finalText)

class Classifier(
    private val inferenceProvider: InferenceProvider,
    private val widgetRepository: WidgetRepository? = null,
    private val cloudClassifier: CloudClassifier? = null,
    private val modelManager: ModelManager? = null
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

            val systemPrompt = PromptBuilder.buildClassificationSystemPrompt(modelManager?.getUserFocus())
            // NonCancellable: the litertlm SDK spawns a native worker thread on each
            // sendMessageAsync. cancelProcess() is advisory — the JNI thread will fire
            // onDone() regardless. If we let cancellation unwind this collect early,
            // engine.close() can run while JNI is still alive → SIGSEGV on onDone.
            withContext(NonCancellable) {
                inferenceProvider.generateWithTools(systemPrompt, prompt, listOf(tool))
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
            }

            val finalText = ThinkingMode.stripThoughtBlocks(rawResponse.toString()).trim()
            val toolExecuted = wasToolExecuted(tool)
            Log.i(
                TAG,
                "stream done: messages=$messageCount toolCalls=$toolCallCount toolExecuted=$toolExecuted rawChars=${rawResponse.length} finalChars=${finalText.length}"
            )
            if (toolExecuted && toolCallCount == 0) {
                Log.i(TAG, "tool executed through automatic tool calling without surfaced message.toolCalls")
            }
            if (shouldWarnAboutUnexpectedProse(finalText, toolExecuted)) {
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
        } catch (e: CancellationException) {
            throw e
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
        if (notifications.isEmpty()) return emptyList()

        if (modelManager?.isCloudEnabled() == true && cloudClassifier != null) {
            Log.i(TAG, "classifyBatch: routing to cloud (${notifications.size} notifications)")
            return cloudClassifier.classifyBatch(notifications)
        }

        if (!inferenceProvider.isReady()) {
            return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        Log.i(TAG, "classifyBatch: count=${notifications.size} promptLen=${prompt.length}")

        val tool = BatchClassifyNotificationTool()

        return try {
            var messageCount = 0
            var toolCallCount = 0

            val classifySystemPrompt = PromptBuilder.buildClassificationSystemPrompt(modelManager?.getUserFocus())
            // NonCancellable — see comment in classify() above for rationale.
            withContext(NonCancellable) {
                inferenceProvider.generateWithTools(classifySystemPrompt, prompt, listOf(tool))
                    .catch { e -> Log.e(TAG, "batch stream error: ${e.message}", e); throw e }
                    .collect { message ->
                        message.toolCalls?.forEachIndexed { i, call ->
                            Log.i(TAG, "batch toolCall[$i]: name=${call.name} args=${call.arguments}")
                            toolCallCount++
                        }
                        messageCount++
                    }
            }

            Log.i(TAG, "batch done: messages=$messageCount toolCalls=$toolCallCount classified=${tool.resultCount()}/${notifications.size}")

            notifications.mapIndexed { i, notification ->
                val (category, reason) = tool.getResult(i + 1)
                    ?: return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
                val resolved = when (category.lowercase().trim()) {
                    "matters" -> ClassificationResult.MATTERS
                    "noise" -> ClassificationResult.NOISE
                    else -> return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
                }
                notification to ClassificationResult(category = resolved, classifiedBy = "llm", reason = reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "batch LLM inference failed", e)
            notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }
    }

    suspend fun classifyAndExtractBatch(
        notifications: List<NotificationEntity>,
        extractionTools: Map<String, ToolSet>
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        if (notifications.isEmpty()) return emptyList()

        if (modelManager?.isCloudEnabled() == true && cloudClassifier != null) {
            val activeCategories = extractionTools.keys.toList()
            Log.i(TAG, "classifyAndExtractBatch: routing to cloud (${notifications.size} notifications, categories=$activeCategories)")
            return cloudClassifier.classifyAndExtractBatch(notifications, activeCategories)
        }

        if (!inferenceProvider.isReady()) {
            return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)

        val classifyTool = BatchClassifyNotificationTool()
        val toolPlan = ExtractionToolPlanner.plan(
            classifyTool = classifyTool,
            extractionTools = extractionTools,
            notifications = notifications
        )

        val allToolCategories = toolPlan.visibleCategories.joinToString(", ")
        Log.i(TAG, "classifyAndExtract: count=${notifications.size} extractions=[$allToolCategories] promptLen=${prompt.length}")

        val systemPrompt = PromptBuilder.buildClassificationSystemPrompt(modelManager?.getUserFocus()) +
            PromptBuilder.buildExtractionAugment(toolPlan.visibleCategories) +
            PromptBuilder.buildBankTransactionAugment(toolPlan.bankIndices)


        return try {
            BatchConversationRunner(
                inferenceProvider = inferenceProvider,
                extractionResultSaver = ExtractionResultSaver(widgetRepository)
            ).run(
                BatchConversationRequest(
                    systemPrompt = systemPrompt,
                    batchPrompt = prompt,
                    notifications = notifications,
                    classifyTool = classifyTool,
                    conversationTools = toolPlan.conversationTools,
                    extractionTools = toolPlan.effectiveExtractionTools
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "classifyAndExtract failed", e)
            notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }
    }
}
