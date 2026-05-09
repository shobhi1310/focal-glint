package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

internal data class BatchConversationRequest(
    val systemPrompt: String,
    val batchPrompt: String,
    val notifications: List<NotificationEntity>,
    val classifyTool: BatchClassifyNotificationTool,
    val conversationTools: List<ToolSet>,
    val extractionTools: Map<String, ToolSet>
)

internal class BatchConversationRunner(
    private val inferenceProvider: InferenceProvider,
    private val extractionResultSaver: ExtractionResultSaver
) {
    suspend fun run(request: BatchConversationRequest): List<Pair<NotificationEntity, ClassificationResult>> {
        val session = inferenceProvider.startConversation(request.systemPrompt, request.conversationTools)
        return session.use {
            runClassificationPass(session, request.batchPrompt, request.classifyTool, request.notifications.size)
            retryMissingClassifications(session, request.classifyTool, request.notifications.size)

            val results = BatchClassificationResultMapper.mapResults(request.notifications, request.classifyTool)
            val stillMissing = BatchClassificationResultMapper.missingIndices(
                request.classifyTool,
                request.notifications.size
            )
            if (stillMissing.isNotEmpty()) {
                Log.w(TAG, "pass1 incomplete after retry; skipping extraction missing=${stillMissing.joinToString(",")}")
                return@use results
            }

            runExtractionPassIfNeeded(session, request, results)
            results
        }
    }

    private suspend fun runClassificationPass(
        session: ConversationSession,
        batchPrompt: String,
        classifyTool: BatchClassifyNotificationTool,
        notificationCount: Int
    ) {
        session.send(PromptBuilder.buildClassificationPassPrompt(batchPrompt))
            .catch { e -> Log.e(TAG, "pass1 error: ${e.message}", e); throw e }
            .collect { message ->
                message.toolCalls?.forEach { call -> Log.i(TAG, "pass1 toolCall: name=${call.name}") }
            }

        Log.i(TAG, "pass1 done: classified=${classifyTool.resultCount()}/$notificationCount")
    }

    private suspend fun retryMissingClassifications(
        session: ConversationSession,
        classifyTool: BatchClassifyNotificationTool,
        notificationCount: Int
    ) {
        repeat(notificationCount) { attempt ->
            val missingIndices = BatchClassificationResultMapper.missingIndices(classifyTool, notificationCount)
            if (missingIndices.isEmpty()) return

            val before = classifyTool.resultCount()
            val retryPrompt = buildClassificationRetryPrompt(missingIndices)
            Log.w(TAG, "pass1 retry ${attempt + 1}: missing=${missingIndices.joinToString(",")}")
            session.send(retryPrompt)
                .catch { e -> Log.w(TAG, "pass1 retry error: ${e.message}", e) }
                .collect { message ->
                    message.toolCalls?.forEach { call -> Log.i(TAG, "pass1 retry toolCall: name=${call.name}") }
                }

            if (classifyTool.resultCount() <= before) {
                Log.w(TAG, "pass1 retry made no progress; classified=${classifyTool.resultCount()}/$notificationCount")
                return
            }
        }
    }

    private suspend fun runExtractionPassIfNeeded(
        session: ConversationSession,
        request: BatchConversationRequest,
        results: List<Pair<NotificationEntity, ClassificationResult>>
    ) {
        val mattersIndices = BatchClassificationResultMapper.mattersIndices(results)
        if (mattersIndices.isEmpty()) return

        val bankIndices = mattersIndices.filter { request.notifications[it - 1].isBankTransaction }
        val extractionPrompt = PromptBuilder.buildExtractionPassPrompt(mattersIndices, bankIndices)

        session.send(extractionPrompt)
            .catch { e -> Log.w(TAG, "pass2 error: ${e.message}", e) }
            .collect { message ->
                message.toolCalls?.forEach { call -> Log.i(TAG, "pass2 toolCall: name=${call.name}") }
            }

        val extractionResults = ExtractionToolFactory.collectResults(request.extractionTools)
        val noExtractionTool = request.extractionTools[ExtractionCategoryRegistry.NONE] as? NoExtractionTool
        Log.i(TAG, "pass2 done: extracted=${extractionResults.size} noExtraction=${noExtractionTool?.calledIndices?.size ?: 0}")
        extractionResultSaver.save(extractionResults, request.notifications)
    }

    private fun buildClassificationRetryPrompt(missingIndices: List<Int>): String {
        val indices = missingIndices.joinToString(", ") { "[$it]" }
        return "Classify the missed notifications at indices $indices now. " +
            "Use the original notification list above. Call classifyNotification exactly once for each listed index. " +
            "category must be exactly 'matters' or 'noise' — no other value. " +
            "Do not call extraction tools or noExtraction in this turn. Output tool calls only."
    }

    private companion object {
        const val TAG = "BatchConversationRunner"
    }
}
