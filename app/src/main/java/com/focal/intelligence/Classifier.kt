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
            inferenceProvider.generateWithTools(classifySystemPrompt, prompt, listOf(tool))
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

        if (modelManager?.isCloudEnabled() == true && cloudClassifier != null) {
            val activeCategories = extractionTools.keys.toList()
            Log.i(TAG, "classifyAndExtractBatch: routing to cloud (${notifications.size} notifications, categories=$activeCategories)")
            return cloudClassifier.classifyAndExtractBatch(notifications, activeCategories)
        }

        if (!inferenceProvider.isReady()) {
            return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        val allToolCategories = extractionTools.keys.joinToString(", ")
        Log.i(TAG, "classifyAndExtract: count=${notifications.size} extractions=[$allToolCategories] promptLen=${prompt.length}")

        val classifyTool = BatchClassifyNotificationTool()
        val allTools = mutableListOf<ToolSet>(classifyTool)
        allTools.addAll(extractionTools.values)

        val bankIndices = notifications.mapIndexedNotNull { i, n ->
            if (n.isBankTransaction) i + 1 else null
        }
        var effectiveExtractionTools = extractionTools
        if (bankIndices.isNotEmpty() && "bank_transaction" !in extractionTools) {
            val bankTools = ExtractionToolFactory.createTools(listOf("bank_transaction"))
            allTools.addAll(bankTools.values)
            effectiveExtractionTools = extractionTools + bankTools
        }

        val systemPrompt = PromptBuilder.buildClassificationSystemPrompt(modelManager?.getUserFocus()) +
            PromptBuilder.buildExtractionAugment(extractionTools.keys.toList()) +
            PromptBuilder.buildBankTransactionAugment(bankIndices)


        return try {
            runBatchPass(systemPrompt, prompt, allTools, classifyTool, effectiveExtractionTools, notifications)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "classifyAndExtract failed", e)
            notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }
    }

    private suspend fun runBatchPass(
        systemPrompt: String,
        prompt: String,
        allTools: List<ToolSet>,
        classifyTool: BatchClassifyNotificationTool,
        effectiveExtractionTools: Map<String, ToolSet>,
        notifications: List<NotificationEntity>
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        val session = inferenceProvider.startConversation(systemPrompt, allTools)
        return session.use {
            // Pass 1 — classification only
            session.send(prompt)
                .catch { e -> Log.e(TAG, "pass1 error: ${e.message}", e); throw e }
                .collect { message ->
                    message.toolCalls?.forEach { call -> Log.i(TAG, "pass1 toolCall: name=${call.name}") }
                }

            Log.i(TAG, "pass1 done: classified=${classifyTool.resultCount()}/${notifications.size}")

            val results = notifications.mapIndexed { i, notification ->
                val (category, reason) = classifyTool.getResult(i + 1)
                    ?: return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
                val resolved = when (category.lowercase().trim()) {
                    "matters" -> ClassificationResult.MATTERS
                    "noise" -> ClassificationResult.NOISE
                    else -> return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
                }
                notification to ClassificationResult(category = resolved, classifiedBy = "llm", reason = reason)
            }

            // Pass 2 — extraction for all matters in same conversation
            val mattersIndices = results.mapIndexedNotNull { i, (_, r) ->
                if (r.category == ClassificationResult.MATTERS) i + 1 else null
            }

            if (mattersIndices.isNotEmpty()) {
                val bankIndices = mattersIndices.filter { notifications[it - 1].isBankTransaction }
                val extractionPrompt = PromptBuilder.buildExtractionPassPrompt(mattersIndices, bankIndices)

                session.send(extractionPrompt)
                    .catch { e -> Log.w(TAG, "pass2 error: ${e.message}", e) }
                    .collect { message ->
                        message.toolCalls?.forEach { call -> Log.i(TAG, "pass2 toolCall: name=${call.name}") }
                    }

                val extractionResults = ExtractionToolFactory.collectResults(effectiveExtractionTools)
                val noExtractionTool = effectiveExtractionTools["none"] as? NoExtractionTool
                Log.i(TAG, "pass2 done: extracted=${extractionResults.size} noExtraction=${noExtractionTool?.calledIndices?.size ?: 0}")

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
            }

            results
        }
    }
}
