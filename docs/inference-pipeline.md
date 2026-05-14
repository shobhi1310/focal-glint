---
title: LLM inference pipeline
description: How LiteRT-LM runs the Gemma model, the worker queue system, and how Focal keeps inference alive
---

# LLM inference pipeline

Focal uses LiteRT-LM to run Gemma 4 E2B locally on Android. This page covers how the model is loaded, how inference is invoked, and how the worker pipeline keeps everything running.

---

## Model loading

### Model selection

Focal supports one model variant, defined in `ModelVariant`:

```kotlin
GEMMA4_E2B(
    fileName = "gemma-4-E2B-it.litertlm",
    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
    displayName = "Gemma 4 E2B",
    sizeLabel = "2.58 GB",
    maxContextTokens = 8 * 1024
)
```

### Download

The model is downloaded via Android's `DownloadManager`:

```kotlin
suspend fun downloadModel(variant: ModelVariant, onProgress: (Int) -> Unit)
```

- Downloads from the Hugging Face URL to `{externalFilesDir}/models/`
- Shows a system download notification with progress
- Supports WiFi and mobile data
- Polls `DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR` every second for progress updates

### Loading into memory

The model is loaded by `LiteRtLmProvider.initialize()`:

```kotlin
interface InferenceProvider {
    suspend fun initialize(modelPath: String, useGpu: Boolean = true, maxContextTokens: Int = 8192)
    suspend fun restart(modelPath: String, useGpu: Boolean, maxContextTokens: Int = 8192)
    fun isReady(): Boolean
    fun close()
}
```

- The 2.58 GB model file is loaded into RAM
- GPU acceleration is used by default (configurable in settings)
- `maxContextTokens` is configurable: 4K–8K, controlled by a slider in Developer Settings
- `restart()` tears down and re-initializes the engine (used when changing backend or context size)

---

## InferenceProvider interface

All LLM access goes through this interface:

```kotlin
interface InferenceProvider {
    suspend fun initialize(modelPath: String, useGpu: Boolean, maxContextTokens: Int)
    suspend fun restart(modelPath: String, useGpu: Boolean, maxContextTokens: Int)
    suspend fun generate(prompt: String, maxTokens: Int = 256, waitIfBusy: Boolean = true): String
    suspend fun generateWithTools(
        systemInstruction: String,
        prompt: String,
        tools: List<ToolSet>,
        waitIfBusy: Boolean = true,
        automaticToolCalling: Boolean = false
    ): Flow<Message>
    suspend fun startConversation(
        systemInstruction: String,
        tools: List<ToolSet>,
        waitIfBusy: Boolean = true
    ): ConversationSession
    fun isReady(): Boolean
    fun close()
}
```

Three interaction modes:

| Mode | Method | Used for |
|---|---|---|
| Simple text generation | `generate()` | Testing, one-off summaries |
| Tool-based generation | `generateWithTools()` | Classification, extraction, narrative |
| Multi-turn conversation | `startConversation()` | Complex extraction flows |

---

## Input sanitization boundary

Every method that accepts a user or system prompt enforces input sanitization before crossing into native code. The comment in the interface definition spells out the contract:

```
Every prompt-bearing entry point (generate, generateWithTools,
startConversation().send) must sanitize input before any JNI hop
into native code. Java's UTF-16 strings can carry orphan surrogates
and embedded NULs that survive into JNI's Modified UTF-8 output
and crash strict UTF-8 parsers (nlohmann, etc.) with SIGABRT.
```

`String.sanitizeForJni()` is called at every boundary. No caller needs to know about it — the inference provider handles it transparently.

---

## Streaming and NonCancellable

### How streaming works

`generateWithTools()` returns a `Flow<Message>`. The Flow emits messages as the model generates them:

```kotlin
inferenceProvider.generateWithTools(systemPrompt, prompt, listOf(tool))
    .catch { e -> Log.e(TAG, "stream error", e); throw e }
    .collect { message ->
        message.toolCalls?.forEach { call ->
            // Process each tool call as it arrives
        }
    }
```

### The NonCancellable requirement

All `generateWithTools()` calls with `automaticToolCalling = true` run inside `withContext(NonCancellable)`. This is not optional:

```
The litertlm SDK spawns a native worker thread on each sendMessageAsync.
cancelProcess() is advisory — the JNI thread will fire onDone() regardless.
If we let cancellation unwind collect() early, engine.close() can run while
JNI is still alive → SIGSEGV in JniMessageCallbackImpl.onDone.
```

With `automaticToolCalling = false`, the same pattern is followed for safety — the stream can't be cleanly cancelled mid-tool-call.

---

## InferenceWorkQueue

Focal uses a priority-based queue to order inference work:

```kotlin
@Singleton
class InferenceWorkQueue @Inject constructor() {
    private val queue = PriorityBlockingQueue<InferenceWorkItem>()
    private val enqueuedTypes = mutableSetOf<WorkType>()
}
```

### Work types and priorities

| WorkType | Priority | Purpose |
|---|---|---|
| `CLASSIFY_PENDING` | HIGH | Classify all uncategorized notifications |
| `GENERATE_NARRATIVES` | LOW | Regenerate topic narratives from dirty topics |

### Deduplication

The queue maintains an `enqueuedTypes` set. If `CLASSIFY_PENDING` is already enqueued, a second `enqueue(CLASSIFY_PENDING)` is silently ignored:

```kotlin
@Synchronized
fun enqueue(type: WorkType, priority: WorkPriority): Boolean {
    if (type in enqueuedTypes) {
        Log.d(TAG, "Already enqueued: $type, skipping")
        return false
    }
    // ...
}
```

### FIFO within same priority

`InferenceWorkItem` implements `Comparable`:

```kotlin
override fun compareTo(other: InferenceWorkItem): Int {
    val p = priority.level.compareTo(other.priority.level)
    if (p != 0) return p
    return createdAt.compareTo(other.createdAt) // FIFO
}
```

### Preemption

Narrative generation (`GENERATE_NARRATIVES`) checks between each topic whether high-priority work has arrived. If it has, the narrative task re-enqueues itself:

```kotlin
if (workQueue.hasHighPriority()) {
    workQueue.enqueue(WorkType.GENERATE_NARRATIVES, WorkPriority.LOW)
    return@doNarrativeGeneration true
}
```

This means classification always wins. If 50 notifications arrive during narrative generation, the narratives pause, classification runs, and then narratives resume.

---

## InferenceWorker lifecycle

`InferenceWorker` is a `@HiltWorker` that receives all intelligence components via DI.

### doWork() — the main loop

```kotlin
override suspend fun doWork(): Result {
    // 1. Start foreground service if engine enabled
    if (modelManager.isEngineEnabled()) {
        startForegroundService(LlmForegroundService::class.java)
    }

    // 2. Always enqueue classification
    workQueue.enqueue(WorkType.CLASSIFY_PENDING, WorkPriority.HIGH)

    // 3. Process queue in priority order
    while (!workQueue.isEmpty()) {
        val item = workQueue.poll() ?: break
        when (item.type) {
            CLASSIFY_PENDING -> doClassification()
            GENERATE_NARRATIVES -> doNarrativeGeneration()
        }
    }

    return Result.success()
}
```

### doClassification() — the classification phase

1. **Re-apply rules to all recent notifications** — catches rule updates since last cycle
2. **Query pending notifications** — uncategorized + bank transactions needing extraction
3. **Wait for LLM readiness** — up to 45 seconds if model is still loading
4. **Batch classification** — process in chunks of 10 with `classifyAndExtractBatch()` or `classifyBatch()`
5. **Save bank transactions** — creates `TransactionEntity` rows from bank extraction data
6. **Run topic generation** — generates topics from newly classified matters
7. **Enqueue narrative generation** — as LOW priority for after classification is done
8. **Correlate transactions** — matches merchants to apps
9. **Compute widgets** — refreshes all Pulse widget states

### doNarrativeGeneration() — the narrative phase

1. Check LLM readiness
2. Process each dirty topic one at a time via `TopicNarrativeProcessor`
3. After each topic, check for high-priority work and yield if found

---

## EngineWarmupCoordinator

Ensures the embedding engine and LLM engine are warmed up before use:

```kotlin
class EngineWarmupCoordinator(
    private val embeddingProvider: SwitchableEmbeddingProvider,
    private val inferenceProvider: InferenceProvider,
    private val modelManager: ModelManager
)

suspend fun warmEmbeddings()  // Ensures embedding model is loaded
suspend fun warmUp(): Boolean // Ensures LLM model is loaded, returns true if warm
```

Called from `MainActivity` on app start and from `InferenceWorker` before topic generation.

---

## Worker triggering

| Trigger | Mechanism | When |
|---|---|---|
| New notification | `FocalNotificationListener.onNotificationPosted()` → `WorkManager.enqueueUniqueWork()` | Every time a notification arrives |
| Pending rebuild | `LlmForegroundService.onStartCommand()` | When engine reconnects after a context change |
| Daily reset | `DailyResetWorker` → `WorkManager.enqueueUniqueWork()` | 2 AM every day |
| Pull-to-refresh | `DigestViewModel.onRefresh()` → `WorkManager.enqueueUniqueWork()` | User manually refreshes |

All triggers use `ExistingWorkPolicy.REPLACE` with unique name `"focal_inference"` — only one worker runs at a time.

---

## Why the foreground service exists

Android kills apps in the background. When Gemma 4 E2B (2.58 GB) is loaded into RAM, killing the process means:

1. The model is unloaded
2. Loading it back takes 10–30 seconds
3. Any in-flight inference work is lost

The foreground service prevents this with a persistent notification. The notification is silent and minimal-priority — users barely notice it.

On Android 14+, the service uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, which requires declaring the use case in the manifest.

---

## What to read next

- [Classification & rules](classification-and-rules.html) — what the LLM is classifying
- [Embeddings & clustering](embeddings-and-clustering.html) — how embeddings flow through JNI
- [Architecture & internals](architecture.html) — all the backend components
- [Technology decisions](tech-decisions.html) — why the architecture looks this way
