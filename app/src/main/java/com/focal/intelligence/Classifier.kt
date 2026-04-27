package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.WidgetRepository
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

private const val TAG = "Classifier"
private const val CLASSIFICATION_SYSTEM =
    "You classify one Android notification using tool calls only. Call classifyNotification exactly once. Use category exactly 'matters' for direct human messages/calls, OTP/security alerts, banking/payment confirmations, orders/deliveries, bookings/travel, meetings, work tasks, mentions, or assigned actions. Use category exactly 'noise' for ads, offers, engagement prompts, newsletters, feeds, news/weather, social posts/reels/reactions, job/course promos, vague status-only items, or media-only items. Use a short snake_case reason. No prose."
private const val BATCH_CLASSIFICATION_SYSTEM =
    "You classify Android notifications using tool calls only. For every [index], call classifyNotification exactly once with the same index. Use category exactly 'matters' for direct human messages/calls, OTP/security alerts, banking/payment confirmations, orders/deliveries, bookings/travel, meetings, work tasks, mentions, or assigned actions. Use category exactly 'noise' for ads, offers, engagement prompts, newsletters, feeds, news/weather, social posts/reels/reactions, job/course promos, vague status-only items, or media-only items. Use a short snake_case reason. No prose."
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
    private val widgetRepository: WidgetRepository? = null
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

        if (!inferenceProvider.isReady()) {
            return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        Log.i(TAG, "classifyBatch: count=${notifications.size} promptLen=${prompt.length}")

        val tool = BatchClassifyNotificationTool()

        return try {
            var messageCount = 0
            var toolCallCount = 0

            inferenceProvider.generateWithTools(BATCH_CLASSIFICATION_SYSTEM, prompt, listOf(tool))
                .catch { e -> Log.e(TAG, "batch stream error: ${e.message}", e); throw e }
                .collect { message ->
                    message.toolCalls?.forEachIndexed { i, call ->
                        Log.i(TAG, "batch toolCall[$i]: name=${call.name} args=${call.arguments}")
                        toolCallCount++
                    }
                    messageCount++
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
        if (!inferenceProvider.isReady()) {
            return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        val allToolCategories = extractionTools.keys.joinToString(", ")
        Log.i(TAG, "classifyAndExtract: count=${notifications.size} extractions=[$allToolCategories] promptLen=${prompt.length}")

        val classifyTool = BatchClassifyNotificationTool()
        val allTools = mutableListOf<ToolSet>(classifyTool)
        allTools.addAll(extractionTools.values)

        val systemPrompt = BATCH_CLASSIFICATION_SYSTEM +
            "\n\nAfter all classifications, internally compare notifications across the whole batch before choosing extraction calls. " +
            "Do not output reasoning. Extraction calls represent distinct real-world events, not notification count. " +
            "If multiple notifications describe the same event across SMS, Gmail, bank app, payment app, or other channels, call the extraction tool only once using the most authoritative notification index. " +
            "For finance duplicates, same rounded amount plus same direction in one batch usually means one transaction; prefer bank SMS, then bank alert email, then receipt email, then app push. " +
            "For personal duplicates, collapse same sender plus same channel into one extractPersonal call and set count to the number of collapsed messages. " +
            "For logistics duplicates, collapse same merchant/order flow and keep the latest or most advanced status. " +
            "For work duplicates, collapse same sender plus same work entity/thread. " +
            "Call extraction tools only for 'matters' notifications and only when required fields are explicit. " +
            "A single notification may call multiple extraction tools only when it truly contains multiple event types. " +
            "Available extraction categories: $allToolCategories. No prose."

        return try {
            var messageCount = 0
            inferenceProvider.generateWithTools(systemPrompt, prompt, allTools)
                .catch { e -> Log.e(TAG, "extract batch error: ${e.message}", e); throw e }
                .collect { message ->
                    message.toolCalls?.forEachIndexed { i, call ->
                        Log.i(TAG, "extract toolCall[$i]: name=${call.name}")
                    }
                    messageCount++
                }

            val extractionResults = ExtractionToolFactory.collectResults(extractionTools)
            Log.i(TAG, "extract done: messages=$messageCount classified=${classifyTool.resultCount()}/${notifications.size} extracted=${extractionResults.size}")

            if (extractionResults.isNotEmpty() && widgetRepository != null) {
                val entities = extractionResults.mapNotNull { result ->
                    val notif = notifications.getOrNull(result.notificationIndex - 1) ?: return@mapNotNull null
                    ExtractedDataEntity(
                        notificationId = notif.id,
                        category = result.category,
                        data = result.dataJson,
                        appPackage = notif.packageName
                    )
                }
                widgetRepository.saveExtractedData(entities)
                Log.i(TAG, "Saved ${entities.size} extracted data rows")
            }

            notifications.mapIndexed { i, notification ->
                val (category, reason) = classifyTool.getResult(i + 1)
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
            Log.e(TAG, "classifyAndExtract failed", e)
            notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }
    }
}
