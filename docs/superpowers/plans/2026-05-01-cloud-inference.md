# Cloud Inference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cloud classification path using llama.cpp's OpenAI-compatible API, remove Gemma 3 1B, and let users toggle between on-device Gemma 4 E2B and a hosted fine-tuned model for classification + extraction.

**Architecture:** New `CloudClassifier` speaks OpenAI `/v1/chat/completions` via OkHttp. `Classifier` routes `classifyBatch`/`classifyAndExtractBatch` through cloud or local based on a preference toggle. Narrative generation and embeddings stay on-device always. Two user toggles in Tune screen: cloud inference on/off and data consent on/off.

**Tech Stack:** OkHttp 4.x for HTTP, kotlinx-serialization-json for JSON, existing Hilt DI, SharedPreferences for toggles.

---

### Task 1: Add OkHttp Dependency and Remove Gemma 3 1B

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/focal/intelligence/ModelManager.kt`
- Modify: `app/src/main/java/com/focal/ui/setup/SetupViewModel.kt`

- [ ] **Step 1: Add OkHttp dependency to build.gradle.kts**

In `app/build.gradle.kts`, add after the kotlinx-serialization-json dependency:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

- [ ] **Step 2: Remove GEMMA3_1B from ModelVariant enum**

In `ModelManager.kt`, remove the entire `GEMMA3_1B` entry from the `ModelVariant` enum (lines 19-25), leaving only `GEMMA4_E2B`.

The enum should become:

```kotlin
enum class ModelVariant(
    val fileName: String,
    val url: String,
    val displayName: String,
    val sizeLabel: String,
    val maxContextTokens: Int
) {
    GEMMA4_E2B(
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B",
        sizeLabel = "2.58 GB",
        maxContextTokens = 8192
    )
}
```

- [ ] **Step 3: Update SetupUiState default**

In `SetupViewModel.kt`, change the default `selectedModel` from `ModelVariant.GEMMA3_1B` to `ModelVariant.GEMMA4_E2B`:

```kotlin
data class SetupUiState(
    val notificationAccessGranted: Boolean = false,
    val batteryOptimizationDisabled: Boolean = false,
    val selectedModel: ModelVariant = ModelVariant.GEMMA4_E2B,
    // ... rest unchanged
)
```

- [ ] **Step 4: Add cloud/consent preference methods to ModelManager**

In `ModelManager.kt`, add these methods after the existing `saveBackendPreference` method:

```kotlin
    fun isCloudEnabled(): Boolean {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("cloud_inference_enabled", false)
    }

    fun setCloudEnabled(enabled: Boolean) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("cloud_inference_enabled", enabled).apply()
    }

    fun isDataConsentEnabled(): Boolean {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("cloud_data_consent", false)
    }

    fun setDataConsentEnabled(enabled: Boolean) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("cloud_data_consent", enabled).apply()
    }
```

- [ ] **Step 5: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -10`

There may be compilation errors if any code references `ModelVariant.GEMMA3_1B` — fix any such references by replacing with `ModelVariant.GEMMA4_E2B`. Check `ModelManager.kt` for `getSelectedVariant()` which may return a saved "GEMMA3_1B" string from SharedPreferences — add a fallback:

In `getSelectedVariant()`, ensure the `valueOf` call handles the removed variant gracefully:

```kotlin
fun getSelectedVariant(): ModelVariant? {
    val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
    val name = prefs.getString("selected_model_variant", null) ?: return null
    return try {
        ModelVariant.valueOf(name)
    } catch (_: IllegalArgumentException) {
        null
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/focal/intelligence/ModelManager.kt app/src/main/java/com/focal/ui/setup/SetupViewModel.kt
git commit -m "feat(cloud): remove Gemma 3 1B, add OkHttp dep, add cloud/consent preferences"
```

---

### Task 2: Create CloudApiModels.kt

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/CloudApiModels.kt`

- [ ] **Step 1: Create OpenAI-format request/response data classes**

```kotlin
package com.focal.intelligence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = "auto",
    @SerialName("max_tokens") val maxTokens: Int = 512,
    val temperature: Double = 0.1
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallResponse>? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

@Serializable
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertyDefinition>,
    val required: List<String>
)

@Serializable
data class PropertyDefinition(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: ChatMessage
)

@Serializable
data class ToolCallResponse(
    val id: String? = null,
    val type: String? = "function",
    val function: FunctionCallResponse
)

@Serializable
data class FunctionCallResponse(
    val name: String,
    val arguments: String
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/CloudApiModels.kt
git commit -m "feat(cloud): add OpenAI-format request/response data classes"
```

---

### Task 3: Create CloudClassifier.kt

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/CloudClassifier.kt`

- [ ] **Step 1: Create the cloud classifier**

```kotlin
package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.WidgetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "CloudClassifier"

class CloudClassifier(
    private val modelManager: ModelManager,
    private val widgetRepository: WidgetRepository? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun classifyBatch(
        notifications: List<NotificationEntity>
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        if (notifications.isEmpty()) return emptyList()

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        val tools = listOf(buildClassifyToolDefinition())

        val systemPrompt = "You are a notification classifier. For each notification below, " +
            "think through what kind of notification it is — for example: personal message, " +
            "marketing/promotional, transactional (receipt/OTP/delivery), service update, " +
            "social media post, news/alert, or other. Then decide whether it genuinely matters " +
            "to this specific user personally, or is just noise. Call classifyNotification once " +
            "for each notification using its [index]. Category must be exactly 'matters' or 'noise'. No prose."

        Log.i(TAG, "classifyBatch: count=${notifications.size} promptLen=${prompt.length}")

        val toolCalls = executeRequest(systemPrompt, prompt, tools) ?: run {
            Log.w(TAG, "classifyBatch: request failed, returning pending")
            return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }

        return mapClassificationResults(notifications, toolCalls)
    }

    suspend fun classifyAndExtractBatch(
        notifications: List<NotificationEntity>,
        activeCategories: List<String>
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        if (notifications.isEmpty()) return emptyList()

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        val tools = mutableListOf(buildClassifyToolDefinition())
        if ("finance" in activeCategories) tools.add(buildExtractFinanceToolDefinition())
        if ("work" in activeCategories) tools.add(buildExtractWorkToolDefinition())
        if ("personal" in activeCategories) tools.add(buildExtractPersonalToolDefinition())
        if ("logistics" in activeCategories) tools.add(buildExtractLogisticsToolDefinition())

        val categoriesStr = activeCategories.joinToString(", ")
        val systemPrompt = "You are a notification classifier. For each notification below, " +
            "think through what kind of notification it is — for example: personal message, " +
            "marketing/promotional, transactional (receipt/OTP/delivery), service update, " +
            "social media post, news/alert, or other. Then decide whether it genuinely matters " +
            "to this specific user personally, or is just noise. Call classifyNotification once " +
            "for each notification using its [index]. Category must be exactly 'matters' or 'noise'. No prose." +
            "\n\nAfter classifying each notification, if it is 'matters', also call the appropriate " +
            "extraction tool(s) for it. A notification can match multiple extraction tools " +
            "(e.g., a food delivery payment is both finance and logistics). " +
            "Available extraction categories: $categoriesStr."

        Log.i(TAG, "classifyAndExtract: count=${notifications.size} categories=[$categoriesStr]")

        val toolCalls = executeRequest(systemPrompt, prompt, tools) ?: run {
            Log.w(TAG, "classifyAndExtract: request failed, returning pending")
            return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }

        saveExtractionResults(notifications, toolCalls)
        return mapClassificationResults(notifications, toolCalls)
    }

    private suspend fun executeRequest(
        systemPrompt: String,
        userPrompt: String,
        tools: List<ToolDefinition>
    ): List<ToolCallResponse>? = withContext(Dispatchers.IO) {
        val request = ChatCompletionRequest(
            model = MODEL_NAME,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            ),
            tools = tools,
            toolChoice = "auto",
            maxTokens = 512,
            temperature = 0.1
        )

        val body = json.encodeToString(ChatCompletionRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())

        val consent = modelManager.isDataConsentEnabled()
        val httpRequest = Request.Builder()
            .url("$BASE_URL$CHAT_COMPLETIONS_PATH")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Focal-Consent", consent.toString())
            .post(body)
            .build()

        try {
            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                return@withContext null
            }
            val responseBody = response.body?.string() ?: return@withContext null
            val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
            val calls = parsed.choices.firstOrNull()?.message?.toolCalls
            Log.i(TAG, "Response: ${calls?.size ?: 0} tool calls")
            calls
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}", e)
            null
        }
    }

    private fun mapClassificationResults(
        notifications: List<NotificationEntity>,
        toolCalls: List<ToolCallResponse>
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        val classifyResults = mutableMapOf<Int, Pair<String, String>>()

        for (call in toolCalls) {
            if (call.function.name == "classifyNotification") {
                try {
                    val args = json.parseToJsonElement(call.function.arguments)
                    val obj = args as? kotlinx.serialization.json.JsonObject ?: continue
                    val index = obj["index"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: continue
                    val category = obj["category"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: continue
                    val reason = obj["reason"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
                    classifyResults[index] = category to reason
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse classifyNotification args: ${call.function.arguments}", e)
                }
            }
        }

        Log.i(TAG, "Parsed ${classifyResults.size}/${notifications.size} classifications")

        return notifications.mapIndexed { i, notification ->
            val (category, reason) = classifyResults[i + 1]
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
        toolCalls: List<ToolCallResponse>
    ) {
        val extractionEntities = mutableListOf<ExtractedDataEntity>()

        for (call in toolCalls) {
            val name = call.function.name
            val category = when {
                name == "extractFinance" -> "finance"
                name == "extractWork" -> "work"
                name == "extractPersonal" -> "personal"
                name == "extractLogistics" -> "logistics"
                else -> continue
            }
            try {
                val args = json.parseToJsonElement(call.function.arguments)
                val obj = args as? kotlinx.serialization.json.JsonObject ?: continue
                val index = obj["index"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: continue
                val notif = notifications.getOrNull(index - 1) ?: continue

                val dataJson = call.function.arguments
                extractionEntities.add(ExtractedDataEntity(
                    notificationId = notif.id,
                    category = category,
                    data = dataJson,
                    appPackage = notif.packageName
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse extraction args: ${call.function.arguments}", e)
            }
        }

        if (extractionEntities.isNotEmpty() && widgetRepository != null) {
            widgetRepository.saveExtractedData(extractionEntities)
            Log.i(TAG, "Saved ${extractionEntities.size} extracted data rows")
        }
    }

    companion object {
        const val BASE_URL = "https://inference.focal.app"
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
        const val MODEL_NAME = "gemma-4-finetuned"
        const val TIMEOUT_SECONDS = 30L
    }
}

private fun buildClassifyToolDefinition() = ToolDefinition(
    function = FunctionDefinition(
        name = "classifyNotification",
        description = "Classify a notification from the batch by its index",
        parameters = FunctionParameters(
            properties = mapOf(
                "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                "category" to PropertyDefinition(type = "string", description = "Classification result", enum = listOf("matters", "noise")),
                "reason" to PropertyDefinition(type = "string", description = "Short reason for classification")
            ),
            required = listOf("index", "category", "reason")
        )
    )
)

private fun buildExtractFinanceToolDefinition() = ToolDefinition(
    function = FunctionDefinition(
        name = "extractFinance",
        description = "Extract financial transaction data from a notification about money, payments, or banking",
        parameters = FunctionParameters(
            properties = mapOf(
                "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                "amount" to PropertyDefinition(type = "number", description = "Transaction amount"),
                "merchant" to PropertyDefinition(type = "string", description = "Merchant or payee name"),
                "category" to PropertyDefinition(type = "string", description = "Spending category", enum = listOf("food", "transport", "shopping", "bills", "transfer", "other")),
                "direction" to PropertyDefinition(type = "string", description = "Transaction direction", enum = listOf("debit", "credit"))
            ),
            required = listOf("index", "amount", "merchant", "category", "direction")
        )
    )
)

private fun buildExtractWorkToolDefinition() = ToolDefinition(
    function = FunctionDefinition(
        name = "extractWork",
        description = "Extract work item data from a notification about PRs, issues, code reviews, or work tasks",
        parameters = FunctionParameters(
            properties = mapOf(
                "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                "entity" to PropertyDefinition(type = "string", description = "Work entity identifier"),
                "sender" to PropertyDefinition(type = "string", description = "Person who sent or triggered this"),
                "action" to PropertyDefinition(type = "string", description = "Action type", enum = listOf("review_requested", "merged", "commented", "assigned", "mentioned", "other")),
                "repo" to PropertyDefinition(type = "string", description = "Repository or project name")
            ),
            required = listOf("index", "entity", "sender", "action", "repo")
        )
    )
)

private fun buildExtractPersonalToolDefinition() = ToolDefinition(
    function = FunctionDefinition(
        name = "extractPersonal",
        description = "Extract personal contact data from a notification about calls, messages, or personal communication",
        parameters = FunctionParameters(
            properties = mapOf(
                "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                "sender" to PropertyDefinition(type = "string", description = "Name of the person"),
                "channel" to PropertyDefinition(type = "string", description = "Communication channel", enum = listOf("call", "message", "email", "other")),
                "count" to PropertyDefinition(type = "integer", description = "Number of attempts or messages"),
                "snippet" to PropertyDefinition(type = "string", description = "Brief content snippet")
            ),
            required = listOf("index", "sender", "channel", "count", "snippet")
        )
    )
)

private fun buildExtractLogisticsToolDefinition() = ToolDefinition(
    function = FunctionDefinition(
        name = "extractLogistics",
        description = "Extract delivery or logistics data from a notification about orders, shipments, or tracking",
        parameters = FunctionParameters(
            properties = mapOf(
                "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                "item" to PropertyDefinition(type = "string", description = "Item or order description"),
                "merchant" to PropertyDefinition(type = "string", description = "Merchant or delivery service"),
                "status" to PropertyDefinition(type = "string", description = "Delivery status", enum = listOf("ordered", "shipped", "out_for_delivery", "delivered", "cancelled")),
                "etaMinutes" to PropertyDefinition(type = "integer", description = "ETA in minutes, -1 if unknown")
            ),
            required = listOf("index", "item", "merchant", "status", "etaMinutes")
        )
    )
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/CloudClassifier.kt
git commit -m "feat(cloud): add CloudClassifier with OkHttp client for llama.cpp endpoint"
```

---

### Task 4: Modify Classifier to Route Through Cloud

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/Classifier.kt`

- [ ] **Step 1: Add CloudClassifier dependency and routing**

Add `CloudClassifier` as a constructor parameter:

```kotlin
class Classifier(
    private val inferenceProvider: InferenceProvider,
    private val widgetRepository: WidgetRepository? = null,
    private val cloudClassifier: CloudClassifier? = null,
    private val modelManager: ModelManager? = null
)
```

Modify `classifyBatch` to check cloud routing. At the start of the method, after the empty check and before the isReady check:

```kotlin
    suspend fun classifyBatch(notifications: List<NotificationEntity>): List<Pair<NotificationEntity, ClassificationResult>> {
        if (notifications.isEmpty()) return emptyList()

        if (modelManager?.isCloudEnabled() == true && cloudClassifier != null) {
            Log.i(TAG, "classifyBatch: routing to cloud (${notifications.size} notifications)")
            return cloudClassifier.classifyBatch(notifications)
        }

        if (!inferenceProvider.isReady()) {
            // ... existing local fallback
```

Same pattern for `classifyAndExtractBatch`:

```kotlin
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
            // ... existing local fallback
```

Also remove the debug logging that was added during investigation (the `message.toString().take(500)` and systemPrompt logging lines).

- [ ] **Step 2: Update IntelligenceModule to provide CloudClassifier and updated Classifier**

In `IntelligenceModule.kt`:

Add CloudClassifier provider:

```kotlin
    @Provides
    @Singleton
    fun provideCloudClassifier(modelManager: ModelManager, widgetRepository: WidgetRepository): CloudClassifier {
        return CloudClassifier(modelManager, widgetRepository)
    }
```

Update Classifier provider to include CloudClassifier and ModelManager:

```kotlin
    @Provides
    @Singleton
    fun provideClassifier(
        inferenceProvider: InferenceProvider,
        widgetRepository: WidgetRepository,
        cloudClassifier: CloudClassifier,
        modelManager: ModelManager
    ): Classifier {
        return Classifier(inferenceProvider, widgetRepository, cloudClassifier, modelManager)
    }
```

- [ ] **Step 3: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/Classifier.kt app/src/main/java/com/focal/di/IntelligenceModule.kt
git commit -m "feat(cloud): route classification through cloud when enabled"
```

---

### Task 5: Add INFERENCE Section to TuneScreen

**Files:**
- Modify: `app/src/main/java/com/focal/ui/tune/TuneScreen.kt`
- Modify: `app/src/main/java/com/focal/ui/tune/TuneViewModel.kt`

- [ ] **Step 1: Add cloud/consent state to TuneViewModel**

In `TuneViewModel.kt`, update `TuneUiState`:

```kotlin
data class TuneUiState(
    val apps: List<TuneAppItem> = emptyList(),
    val snackbarMessage: String? = null,
    val cloudEnabled: Boolean = false,
    val dataConsentEnabled: Boolean = false
)
```

Add `ModelManager` to the constructor:

```kotlin
@HiltViewModel
class TuneViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val notificationRepository: NotificationRepository,
    private val modelManager: ModelManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
```

Add import: `import com.focal.intelligence.ModelManager`

In `loadApps()`, after setting the apps state, also set cloud state:

```kotlin
            _uiState.value = _uiState.value.copy(
                apps = apps,
                cloudEnabled = modelManager.isCloudEnabled(),
                dataConsentEnabled = modelManager.isDataConsentEnabled()
            )
```

Add toggle methods:

```kotlin
    fun onToggleCloud(enabled: Boolean) {
        modelManager.setCloudEnabled(enabled)
        _uiState.value = _uiState.value.copy(
            cloudEnabled = enabled,
            dataConsentEnabled = if (!enabled) false else _uiState.value.dataConsentEnabled
        )
        if (!enabled) {
            modelManager.setDataConsentEnabled(false)
        }
    }

    fun onToggleDataConsent(enabled: Boolean) {
        modelManager.setDataConsentEnabled(enabled)
        _uiState.value = _uiState.value.copy(dataConsentEnabled = enabled)
    }
```

- [ ] **Step 2: Add INFERENCE section to TuneScreen**

In `TuneScreen.kt`, add after the APPEARANCE section (after `item { Spacer(modifier = Modifier.height(8.dp)) }` on line 153) and before the explanation cards:

```kotlin
            // Inference toggle
            item {
                SectionHeader(title = "INFERENCE")
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloud classification",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Use our hosted model for better notification classification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = state.cloudEnabled,
                        onCheckedChange = viewModel::onToggleCloud
                    )
                }
            }
            if (state.cloudEnabled) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Help improve Focal",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Allow anonymized notification data to be used for training.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.dataConsentEnabled,
                            onCheckedChange = viewModel::onToggleDataConsent
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
```

Add import: `import androidx.compose.material3.Switch`

- [ ] **Step 3: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:compileDebugKotlin 2>&1 | tail -10`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/ui/tune/TuneScreen.kt app/src/main/java/com/focal/ui/tune/TuneViewModel.kt
git commit -m "feat(cloud): add INFERENCE section to Tune screen with cloud + consent toggles"
```

---

### Task 6: Clean Up Classifier Debug Logging

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/Classifier.kt`

- [ ] **Step 1: Remove investigation debug logging**

In `Classifier.kt`, in the `classifyAndExtractBatch` method, remove the debug logging lines that were added during the Gemma 3 1B tool-call investigation:

- Remove `Log.d(TAG, "classifyAndExtract systemPrompt: $systemPrompt")`
- Remove `Log.d(TAG, "classifyAndExtract userPrompt: $prompt")`
- Remove `Log.d(TAG, "classifyAndExtract tools: ...")`
- Simplify the message collection back to:

```kotlin
                .collect { message ->
                    message.toolCalls?.forEachIndexed { i, call ->
                        Log.i(TAG, "extract toolCall[$i]: name=${call.name}")
                    }
                    messageCount++
                }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/Classifier.kt
git commit -m "chore: remove debug logging from Classifier"
```

---

### Task 7: Final Build, Test, and Push

- [ ] **Step 1: Full APK build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && gradle :app:testDebugUnitTest 2>&1 | tail -20`
Expected: All tests pass

- [ ] **Step 3: Verify no Gemma 3 1B references remain**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && grep -rn "GEMMA3_1B\|Gemma 3 1B\|gemma3-1b" --include="*.kt" app/src/main/java/ | grep -v test/`
Expected: No matches (only test files may reference it if any)

- [ ] **Step 4: Push to remote**

```bash
git push origin dev-darahas
```
