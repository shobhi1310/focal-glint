---
title: Classification & rules
description: How Focal decides what matters and what's noise using the rules engine and on-device LLM
---

# Classification & rules

Every notification Focal intercepts gets a label: **Matters**, **Noise**, or **Auto**. This page covers the two systems that make that decision . the deterministic rules engine and the LLM classifier.

---

## The three tiers

| Tier | Meaning | What happens |
|---|---|---|
| **Matters** | Personally relevant | Shows up in your daily digest, gets clustered into topics |
| **Noise** | Promotional, generic, irrelevant | Bundled into a footnote count, hidden from topics |
| **Auto** | Let the model decide | Falls through to LLM classification |

The user sets these per-app in **Tune** via the M/A/N pill selector. Tapping M sets the app to always matters, N to always noise, A to auto (model decides).

---

## Rules engine

`RulesEngine` runs before the LLM. It's instant, deterministic, and user-controllable.

### Rule types (priority-ordered)

Rules are checked in this exact order:

```
keyword_match → sender_match → app_match
```

**1. `keyword_match` (highest priority)**

Matches a keyword anywhere in the notification text (title + content + big_text).

```json
{
  "type": "keyword_match",
  "pattern": "flight",
  "category": "matters",
  "confidence": 0.95
}
```

This rule would classify any notification containing "flight" as matters.

**2. `sender_match`**

Matches a sender pattern within a specific app. Checks notification title against the pattern.

```json
{
  "type": "sender_match",
  "app": "com.google.android.apps.messaging",
  "pattern": "HDFC",
  "category": "matters",
  "confidence": 0.95
}
```

This catches bank SMS within the Messages app . the notification title contains the bank's shortcode.

**3. `app_match` (lowest priority)**

Matches all notifications from a specific app.

```json
{
  "type": "app_match",
  "app": "com.instagram.android",
  "category": "noise",
  "confidence": 0.9
}
```

This silently classifies every Instagram notification as noise.

### Hit counting

Every time a rule fires, its `hit_count` is incremented. This gives visibility into which rules are actively doing work.

### "Auto" rules

A rule can specify `"auto"` as its category. This doesn't classify the notification . it explicitly lets it fall through to the LLM. The notification remains uncategorized and the rule is skipped (not counted as a hit).

---

## Default rules

Focal ships with default rules in `DefaultRules.kt` that apply sensible defaults:

- Messaging apps (WhatsApp, Messages) → auto (let model decide which conversations matter)
- Banking apps (GPay, PhonePe, bank apps) → auto (model catches payment notifications)
- News apps, YouTube, game apps → noise
- System apps with spammy notifications → noise

Users can override any default by creating their own rules.

---

## User focus

In Tune settings, users can write (or speak) freeform text describing what they care about. This is injected into the LLM's classification system prompt:

```
User focus:
"Track my UPI spends from GPay and PhonePe. Show me when Shruti or Amma messages. Flag HDFC and ICICI bank alerts."
```

The model uses this to personalize classification. It knows to flag UPI payment notifications, prioritize specific contacts, and surface specific bank alerts . even without explicit rules.

---

## LLM classification (single notification)

`Classifier.classify()` processes one notification at a time using a single tool call.

### The tool definition

```kotlin
@Tool("Classify whether a notification matters to the user personally")
fun classifyNotification(
    @ToolParam("Classification result: 'matters' if personally relevant, 'noise' if generic")
    category: String,
    @ToolParam("Short reason explaining the classification decision")
    reason: String
): Map<String, Any>
```

### The system prompt

```
You are a personal notification triage agent running on a user's phone.
Your task is to classify whether a notification personally matters to the user.

Rules:
- MATTERS if: from a real person, about money you spent/received, about
  orders you placed, about accounts you own, requires your action
- NOISE if: promotional, generic, mass emails, social media likes/follows,
  recommendations, ads

[If user focus is set, it's appended here]

You MUST call the classifyNotification tool with your decision.
```

### The process

1. `PromptBuilder.buildClassificationPrompt(notification)` formats the notification into a structured prompt
2. `inferenceProvider.generateWithTools(systemPrompt, prompt, listOf(tool))` streams the model's response
3. The model calls `classifyNotification(category, reason)` as a tool call
4. After the stream completes, the tool's `lastCategory` and `lastReason` are read
5. The result is returned as a `ClassificationResult`

### Why NonCancellable?

The `collect {}` block that reads the stream runs inside `withContext(NonCancellable)`. This is critical:

```
LiteRT-LM spawns a native worker thread for inference. cancelProcess()
is advisory . the JNI thread will fire onDone() regardless. If we let
coroutine cancellation unwind collect() early, engine.close() can run
while JNI is still alive → SIGSEGV in onDone callback.
```

The model call must complete naturally before the coroutine can be cancelled.

### Tool echo detection

Sometimes the model echoes the tool call syntax in its text output without actually invoking the tool. `isSyntheticToolEcho()` detects this pattern:

- Starts with `"response:"` or `"classifyNotification"` or contains `<tool_call>`
- If detected and the actual tool wasn't executed, an unexpected prose warning is logged

---

## Batch classification

Single classification is slow for many notifications. `classifyBatch()` processes up to 10 notifications simultaneously.

### The batch tool

```kotlin
@Tool("Classify a notification from the batch by its index")
fun classifyNotification(
    @ToolParam("1-based index of the notification being classified")
    index: Int,
    @ToolParam("Must be exactly the word 'matters' or the word 'noise'")
    category: String,
    @ToolParam("Short snake_case reason explaining the classification decision")
    reason: String
): Map<String, Any>
```

The model is given all notifications at once and must call `classifyNotification` for each one, using the index to identify which notification it's referring to.

### Missing index detection

After the stream completes, `BatchClassificationResultMapper.missingIndices()` checks which indices weren't classified:

```kotlin
fun missingIndices(tool: BatchClassifyNotificationTool, notificationCount: Int): List<Int> =
    (1.notificationCount).filter { tool.getResult(it) == null }
```

Missing indices are left in `"pending"` state for the next worker cycle.

### Automatic tool calling is DISABLED

For classification, `automaticToolCalling = false`. This means the model must explicitly invoke tool calls in its text output. The tool call stream is collected and each call is processed by `BatchClassifyNotificationTool`.

This is intentional. Enabling automatic tool calling for classification would mean we can't audit whether every notification was classified . the runtime handles it silently.

---

## Batch classification + extraction

`classifyAndExtractBatch()` combines classification with data extraction in a single LLM pass. This avoids running two separate inference cycles.

It uses `BatchConversationRunner` with `ExtractionToolPlanner` to:

1. Route bank transactions to the bank extraction tool
2. Identify which extraction categories are active (based on user's widgets)
3. Build an augmented system prompt describing available extraction tools
4. Run a two-phase conversation: classification first (to know which are matters), then extraction only for matters notifications

### Automatic tool calling is ENABLED

For the combined classify+extract, `automaticToolCalling = true`. This is necessary because:

- Extractions often require the model to chain multiple tool calls
- The model needs to decide on its own which extraction tools to call based on notification content
- The classification phase must complete before extraction starts

---

## Cloud fallback

`CloudClassifier` provides optional cloud-based classification:

```kotlin
suspend fun classifyBatch(notifications: List<NotificationEntity>): List<Pair<.>>
suspend fun classifyAndExtractBatch(notifications: List<NotificationEntity>, categories: List<String>): List<Pair<.>>
```

Configured in Developer Settings:
- `cloud_inference_enabled` . toggle on/off
- `cloud_endpoint` . API URL
- `cloud_api_key` . authentication
- `cloud_model_name` . model to use

When enabled, `Classifier.classifyBatch()` and `classifyAndExtractBatch()` check `modelManager.isCloudEnabled()` first and delegate to the cloud if true. Cloud results are written to the same database columns as on-device results.

---

## Input sanitization

All prompt text is sanitized before entering native code. Java/Kotlin strings can carry orphan surrogates and embedded NUL bytes that survive JNI's Modified UTF-8 encoding and crash strict C++ UTF-8 parsers.

`String.sanitizeForJni()` strips these characters at every JNI boundary:
- `generate()`, `generateWithTools()`, and `startConversation()->send()` all sanitize before crossing into native code
- This prevents `SIGABRT` from the LiteRT-LM native library
- Callers never need to worry about it . the inference provider handles it transparently

---

## What to read next

- [LLM inference pipeline](inference-pipeline.html) . how the model is actually invoked and managed
- [Extraction & widgets](extraction-and-widgets.html) . what gets extracted and how widgets consume it
- [Architecture & internals](architecture.html) . all the backend plumbing
