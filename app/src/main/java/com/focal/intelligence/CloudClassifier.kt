package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.WidgetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "CloudClassifier"

private val json = Json { ignoreUnknownKeys = true }

class CloudClassifier(
    private val modelManager: ModelManager,
    private val widgetRepository: WidgetRepository?
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun classifyBatch(
        notifications: List<NotificationEntity>
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        if (notifications.isEmpty()) return emptyList()

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        Log.i(TAG, "classifyBatch: count=${notifications.size} promptLen=${prompt.length}")

        val systemPrompt = BATCH_CLASSIFICATION_SYSTEM_PROMPT

        val request = ChatCompletionRequest(
            model = modelManager.getCloudModelName(),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = prompt)
            ),
            tools = listOf(buildClassifyToolDefinition()),
            toolChoice = "auto",
            maxTokens = 512 + (notifications.size * 40),
            temperature = 0.1
        )

        return try {
            val response = executeRequest(request)
            if (response == null) {
                Log.w(TAG, "classifyBatch: network failure, returning all as pending")
                return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending", reason = "cloud_network_error") }
            }
            mapClassificationResults(notifications, response)
        } catch (e: Exception) {
            Log.e(TAG, "classifyBatch failed", e)
            notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending", reason = "cloud_error: ${e.message}") }
        }
    }

    suspend fun classifyAndExtractBatch(
        notifications: List<NotificationEntity>,
        activeCategories: List<String>
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        if (notifications.isEmpty()) return emptyList()

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        val categoriesStr = activeCategories.joinToString(", ")
        Log.i(TAG, "classifyAndExtractBatch: count=${notifications.size} categories=[$categoriesStr] promptLen=${prompt.length}")

        val systemPrompt = BATCH_CLASSIFICATION_SYSTEM_PROMPT +
            "\n\nFor every notification you marked 'matters', also call the appropriate extraction " +
            "tool(s) so the user's widgets can show what happened. A single notification may trigger " +
            "multiple extraction tools when it contains multiple distinct things. When the same " +
            "real-world event appears across several notifications (echoed across SMS, email, or app " +
            "pushes), call the extraction tool only once for the most authoritative source. Available " +
            "extraction categories: $categoriesStr. Output tool calls only."

        val tools = mutableListOf(buildClassifyToolDefinition())
        if ("finance" in activeCategories) tools.add(buildExtractFinanceToolDefinition())
        if ("work" in activeCategories) tools.add(buildExtractWorkToolDefinition())
        if ("personal" in activeCategories) tools.add(buildExtractPersonalToolDefinition())
        if ("logistics" in activeCategories) tools.add(buildExtractLogisticsToolDefinition())

        val request = ChatCompletionRequest(
            model = modelManager.getCloudModelName(),
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = prompt)
            ),
            tools = tools,
            toolChoice = "auto",
            maxTokens = 1024 + (notifications.size * 80),
            temperature = 0.1
        )

        return try {
            val response = executeRequest(request)
            if (response == null) {
                Log.w(TAG, "classifyAndExtractBatch: network failure, returning all as pending")
                return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending", reason = "cloud_network_error") }
            }

            saveExtractionResults(notifications, response)
            mapClassificationResults(notifications, response)
        } catch (e: Exception) {
            Log.e(TAG, "classifyAndExtractBatch failed", e)
            notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending", reason = "cloud_error: ${e.message}") }
        }
    }

    private suspend fun executeRequest(chatRequest: ChatCompletionRequest): ChatCompletionResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = json.encodeToString(chatRequest)
                Log.d(TAG, "executeRequest: bodyLen=${requestBody.length}")

                val baseUrl = modelManager.getCloudEndpoint()
                val apiKey = modelManager.getCloudApiKey()
                val builder = Request.Builder()
                    .url("$baseUrl$CHAT_COMPLETIONS_PATH")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Focal-Consent", modelManager.isDataConsentEnabled().toString())
                if (apiKey.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer $apiKey")
                }
                val httpRequest = builder.build()

                val response = client.newCall(httpRequest).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "HTTP error: ${resp.code} ${resp.message}")
                        return@withContext null
                    }
                    val body = resp.body?.string()
                    if (body == null) {
                        Log.e(TAG, "Empty response body")
                        return@withContext null
                    }
                    Log.d(TAG, "response: ${body.take(500)}")
                    json.decodeFromString<ChatCompletionResponse>(body)
                }
            } catch (e: Exception) {
                Log.e(TAG, "executeRequest network error: ${e.message}", e)
                null
            }
        }
    }

    private fun mapClassificationResults(
        notifications: List<NotificationEntity>,
        response: ChatCompletionResponse
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        val resultMap = mutableMapOf<Int, Pair<String, String>>() // index -> (category, reason)

        val toolCalls = response.choices.firstOrNull()?.message?.toolCalls ?: emptyList()
        Log.i(TAG, "mapClassificationResults: ${toolCalls.size} tool calls")

        for (call in toolCalls) {
            if (call.function.name != "classifyNotification") continue
            try {
                val args = json.parseToJsonElement(call.function.arguments).jsonObject
                val index = args["index"]?.jsonPrimitive?.int ?: continue
                val category = args["category"]?.jsonPrimitive?.content ?: continue
                val reason = args["reason"]?.jsonPrimitive?.content ?: ""
                resultMap[index] = category to reason
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse classify tool call: ${call.function.arguments}", e)
            }
        }

        Log.i(TAG, "classified ${resultMap.size}/${notifications.size} notifications")

        return notifications.mapIndexed { i, notification ->
            val (category, reason) = resultMap[i + 1]
                ?: return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
            val resolved = when (category.lowercase().trim()) {
                "matters" -> ClassificationResult.MATTERS
                "noise" -> ClassificationResult.NOISE
                else -> return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
            }
            notification to ClassificationResult(category = resolved, classifiedBy = "cloud", reason = reason)
        }
    }

    private suspend fun saveExtractionResults(
        notifications: List<NotificationEntity>,
        response: ChatCompletionResponse
    ) {
        val toolCalls = response.choices.firstOrNull()?.message?.toolCalls ?: return
        val extractionEntities = mutableListOf<ExtractedDataEntity>()

        for (call in toolCalls) {
            val name = call.function.name
            val extractionCategory = when (name) {
                "extractFinance" -> "finance"
                "extractWork" -> "work"
                "extractPersonal" -> "personal"
                "extractLogistics" -> "logistics"
                else -> continue
            }

            try {
                val args = json.parseToJsonElement(call.function.arguments).jsonObject
                val index = args["index"]?.jsonPrimitive?.int ?: continue
                val notification = notifications.getOrNull(index - 1) ?: continue

                val dataJson = buildExtractionDataJson(extractionCategory, args)
                extractionEntities.add(
                    ExtractedDataEntity(
                        notificationId = notification.id,
                        category = extractionCategory,
                        data = dataJson,
                        appPackage = notification.packageName
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse extraction tool call: ${call.function.name} ${call.function.arguments}", e)
            }
        }

        if (extractionEntities.isNotEmpty() && widgetRepository != null) {
            widgetRepository.saveExtractedData(extractionEntities)
            Log.i(TAG, "Saved ${extractionEntities.size} extracted data rows from cloud")
        }
    }

    private fun buildExtractionDataJson(category: String, args: JsonObject): String {
        return when (category) {
            "finance" -> {
                val data = FinanceData(
                    amount = args["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    merchant = args["merchant"]?.jsonPrimitive?.content ?: "",
                    category = args["category"]?.jsonPrimitive?.content ?: "other",
                    direction = args["direction"]?.jsonPrimitive?.content ?: "debit"
                )
                Json.encodeToString(data)
            }
            "work" -> {
                val data = WorkData(
                    entity = args["entity"]?.jsonPrimitive?.content ?: "",
                    sender = args["sender"]?.jsonPrimitive?.content ?: "",
                    action = args["action"]?.jsonPrimitive?.content ?: "other",
                    repo = args["repo"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                )
                Json.encodeToString(data)
            }
            "personal" -> {
                val data = PersonalData(
                    sender = args["sender"]?.jsonPrimitive?.content ?: "",
                    channel = args["channel"]?.jsonPrimitive?.content ?: "other",
                    count = args["count"]?.jsonPrimitive?.intOrNull ?: 1,
                    snippet = args["snippet"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                )
                Json.encodeToString(data)
            }
            "logistics" -> {
                val data = LogisticsData(
                    item = args["item"]?.jsonPrimitive?.content ?: "",
                    merchant = args["merchant"]?.jsonPrimitive?.content ?: "",
                    status = args["status"]?.jsonPrimitive?.content ?: "ordered",
                    etaMinutes = args["etaMinutes"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
                )
                Json.encodeToString(data)
            }
            else -> "{}"
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://inference.focal.app"
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
        const val DEFAULT_MODEL_NAME = "gemma-4-it"
        const val TIMEOUT_SECONDS = 60L

        private const val BATCH_CLASSIFICATION_SYSTEM_PROMPT =
            "You are a notification triage assistant. Your job is to decide whether each notification " +
                "meaningfully adds value to the user's day or just demands their attention without giving " +
                "anything back. For every [index] in the list, call classifyNotification exactly once with " +
                "that same index. Mark it 'matters' if a thoughtful person would want to know about it now " +
                "— something asks for their attention, response, awareness, or money. Mark it 'noise' if it " +
                "exists to pull the user into an app, sell them something, surface algorithmic content, or " +
                "repeat what they already know. Use a short snake_case reason. Output tool calls only — no " +
                "prose."
    }
}

private fun buildClassifyToolDefinition(): ToolDefinition {
    return ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "classifyNotification",
            description = "Classify a single notification as matters or noise",
            parameters = FunctionParameters(
                type = "object",
                properties = mapOf(
                    "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                    "category" to PropertyDefinition(type = "string", description = "Classification result", enum = listOf("matters", "noise")),
                    "reason" to PropertyDefinition(type = "string", description = "Short snake_case reason for the classification")
                ),
                required = listOf("index", "category", "reason")
            )
        )
    )
}

private fun buildExtractFinanceToolDefinition(): ToolDefinition {
    return ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "extractFinance",
            description = "Extract financial transaction data from a notification about money, payments, or banking",
            parameters = FunctionParameters(
                type = "object",
                properties = mapOf(
                    "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                    "amount" to PropertyDefinition(type = "number", description = "Transaction amount as a number"),
                    "merchant" to PropertyDefinition(type = "string", description = "Merchant or payee name"),
                    "category" to PropertyDefinition(type = "string", description = "Spending category: food, transport, shopping, bills, transfer, or other"),
                    "direction" to PropertyDefinition(type = "string", description = "Transaction direction: debit or credit", enum = listOf("debit", "credit"))
                ),
                required = listOf("index", "amount", "merchant", "category", "direction")
            )
        )
    )
}

private fun buildExtractWorkToolDefinition(): ToolDefinition {
    return ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "extractWork",
            description = "Extract work item data from a notification about PRs, issues, code reviews, or work tasks",
            parameters = FunctionParameters(
                type = "object",
                properties = mapOf(
                    "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                    "entity" to PropertyDefinition(type = "string", description = "Work entity identifier like PR #482, Issue FOCAL-31, or thread name"),
                    "sender" to PropertyDefinition(type = "string", description = "Person who sent or triggered this"),
                    "action" to PropertyDefinition(type = "string", description = "Action type: review_requested, merged, commented, assigned, mentioned, or other"),
                    "repo" to PropertyDefinition(type = "string", description = "Repository or project name if mentioned")
                ),
                required = listOf("index", "entity", "sender", "action", "repo")
            )
        )
    )
}

private fun buildExtractPersonalToolDefinition(): ToolDefinition {
    return ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "extractPersonal",
            description = "Extract personal contact data from a notification about calls, messages, or personal communication",
            parameters = FunctionParameters(
                type = "object",
                properties = mapOf(
                    "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                    "sender" to PropertyDefinition(type = "string", description = "Name of the person who reached out"),
                    "channel" to PropertyDefinition(type = "string", description = "Communication channel: call, message, email, or other"),
                    "count" to PropertyDefinition(type = "integer", description = "Number of attempts or messages, default 1"),
                    "snippet" to PropertyDefinition(type = "string", description = "Brief content snippet if available")
                ),
                required = listOf("index", "sender", "channel", "count", "snippet")
            )
        )
    )
}

private fun buildExtractLogisticsToolDefinition(): ToolDefinition {
    return ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "extractLogistics",
            description = "Extract delivery or logistics data from a notification about orders, shipments, or tracking",
            parameters = FunctionParameters(
                type = "object",
                properties = mapOf(
                    "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                    "item" to PropertyDefinition(type = "string", description = "Item or order description"),
                    "merchant" to PropertyDefinition(type = "string", description = "Merchant or delivery service name"),
                    "status" to PropertyDefinition(type = "string", description = "Delivery status: ordered, shipped, out_for_delivery, delivered, or cancelled", enum = listOf("ordered", "shipped", "out_for_delivery", "delivered", "cancelled")),
                    "etaMinutes" to PropertyDefinition(type = "integer", description = "Estimated arrival time in minutes, or -1 if unknown")
                ),
                required = listOf("index", "item", "merchant", "status", "etaMinutes")
            )
        )
    )
}
