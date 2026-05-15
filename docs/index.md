---
title: Focal
description: On-device notification intelligence. AI that stays on your phone, protecting your privacy.
---

# Focal

Every day, your phone buzzes with notifications. Some matter. Most don't. Focal intercepts every one of them, figures out which ones you actually care about, groups the important stuff into topics, summarizes them, and even pulls out data you can query later. All of this happens on your phone. No cloud required.

This page tells the full story, from the moment a notification arrives to the moment you see it summarized on screen. At the end, you'll understand every step . and if you want the gritty details, each section links to a deep-dive page.

---

## 1. The listener wakes up

It starts with `FocalNotificationListener`. Android calls it whenever any app posts a notification. The listener is registered as a `NotificationListenerService` . one of the few Android components that can see every notification across every app. The user has to grant this permission manually in system settings.

When a notification arrives, the listener extracts everything useful from it:

- App name and package (`com.whatsapp`, `com.google.android.apps.messaging`)
- Title and body text
- Notification channel / category
- Timestamp
- A content hash to detect duplicate notifications

This raw notification is stored in the `notifications` table in Room with `category = "uncategorized"`. From here, it's waiting to be classified.

> **Deep dive:** [Architecture & internals](architecture.html) covers the database schema, all entity classes, and how they're wired together.

---

## 2. Classification by rules first

Before the AI gets involved, a deterministic rules engine runs first. `RulesEngine` checks every incoming notification against user-defined rules. There are three rule types, checked in priority order:

| Priority | Rule type | What it matches |
|---|---|---|
| 1 (highest) | `keyword_match` | Looks for a keyword in title + body text |
| 2 | `sender_match` | Matches a sender pattern within a specific app |
| 3 (lowest) | `app_match` | Matches the entire app by package name |

Each rule says: "if this matches, classify as **Matters** or **Noise**." If a rule fires, the classification is done . no AI needed. Rules hit counts are tracked so you can see which ones are doing work.

**What about "Auto"?** If a rule specifies `"auto"`, the notification is left unclassified and falls through to the AI. "Auto" is the default for every app . it means "let the model decide."

A separate `CorrectionDao` tracks user corrections: when you manually change a notification from the AI's classification, Focal remembers and applies that knowledge later.

> **Deep dive:** [Classification & rules](classification-and-rules.html) covers the full RulesEngine, default rules, corrections, and the M/A/N triage system.

---

## 3. AI classification with tool calls

Notifications that survive the rules engine (or get marked "auto") go to the on-device LLM. This is where `Classifier` takes over.

The model is Gemma 4 E2B, running through LiteRT-LM. Classification is done using **tool calls** . a structured way to make the model output machine-readable data instead of freeform text.

The model sees a prompt listing every notification in the current batch and is given a tool called `classifyNotification`. It must call this tool once per notification with:

- `index` . which notification it's talking about (1-based)
- `category` . exactly `"matters"` or `"noise"`
- `reason` . a short explanation (snake_case, like `personal_message` or `promotional_email`)

The system prompt includes the user's **focus text** (if set in Tune settings). This shapes the model's decisions. For example, if you wrote "Track my UPI spends from payment apps," the model knows to classify UPI payment notifications as matters.

**Automatic tool calling** is **disabled** for classification. The model is required to explicitly write out each tool call . this gives us a reliable way to check that it classified every notification in the batch. After the stream completes, `BatchClassificationResultMapper` identifies any missing indices and marks them `"pending"` for the next cycle.

An optional **cloud fallback** routes classification to a hosted model if you've enabled it in settings. This is useful on slower devices.

> **Deep dive:** [LLM inference pipeline](inference-pipeline.html) explains how the LLM is invoked, streamed, and how `NonCancellable` prevents crashes. [Classification & rules](classification-and-rules.html) has the full tool call definitions.

---

## 4. Structured extraction

Classification isn't the only thing the model does. Focal can also extract structured data from notifications. This happens in the same inference pass as classification to save time.

Five extraction tools are available:

| Tool | What it extracts | Example notification |
|---|---|---|
| `extractFinance` | Amount, merchant, category, direction | "Paid ₹450 to Swiggy" |
| `extractWork` | Entity, sender, action, repo | "PR #342 opened by a teammate in a repository" |
| `extractPersonal` | Sender name, channel, count, snippet | "A contact: Call me when free" |
| `extractLogistics` | Item, merchant, status, ETA | "Your order is out for delivery" |
| `extractBankTransaction` | Amount, direction, account, bank, merchant | "₹5,000 debited from bank account" |

The model calls these tools only when it determines a notification has extractable data. Results are saved to the `extracted_data` table and later power widgets.

A special `noExtraction` tool is also given to the model. It must call this for every "matters" notification where none of the other extraction tools apply. This ensures the model processes every single matters notification . no silent skips.

Bank SMS are detected by `BankSmsDetector` via a regex pattern before classification even starts. Indian bank SMS follow a predictable format: sender names like `"AX-ICICIB"` plus transaction keywords like "debited" or "credited." These notifications are flagged so the extraction tool is always available for them.

> **Deep dive:** [Extraction & widgets](extraction-and-widgets.html) has every tool definition, the extraction planner, and how the widget compute engine turns raw data into cards.

---

## 5. Embedding the matters

Once notifications are classified, the ones marked "matters" need to be embedded. Embeddings are numerical vectors that capture the semantic meaning of text . similar notifications get similar vectors, which is how we cluster them.

`EmbeddingGemmaLiteRtEmbedder` is the bridge between Kotlin and native code:

1. **Tokenize** . `SentencePieceTokenizer` (C++ via JNI) converts the notification text into token IDs using the SentencePiece model
2. **Format** . `EmbeddingTextFormatter` wraps the text in a query format: `"task: clustering | query: <text>"`
3. **Infer** . `LiteRtEmbedderJni` (C++) feeds token IDs + attention mask into the EmbeddingGemma 300M TFLite model, runs inference, and returns a 768-dimensional float vector
4. **Normalize** . `VectorMath.l2Normalize()` rescales the vector to unit length so cosine similarity works correctly

Only **unembedded** notifications are processed. If the embedding provider isn't ready (model not yet loaded), the notification waits for the next worker cycle.

**Why native code?** Both LiteRT-LM (for the LLM) and LiteRT (for embeddings) ship `libLiteRt.so`. When both are declared as Gradle dependencies, the build system picks one `.so` non-deterministically, causing runtime crashes. The solution was to statically link the embedding pipeline into a single `libfocal_intelligence.so` that uses the same `libLiteRt.so` from the LiteRT-LM AAR at runtime. Same approach as SentencePiece . use the `.so` directly rather than adding a conflicting dependency. The embedding model always runs on **CPU** . even when the LLM runs on GPU . because GPU inference starves the render pipeline when called in rapid succession.

> **Deep dive:** [Embeddings & clustering](embeddings-and-clustering.html) covers the full JNI bridge, the CMake build configuration, tensor buffer management, and vector math.

---

## 6. Clustering into topics

With embeddings ready, `TopicEngine` assigns each matters notification to a topic. The algorithm is:

1. **Channel-first matching** . notifications from the same channel (e.g., same WhatsApp conversation) go to the same topic without needing similarity checks. This keeps conversations together.

2. **Cosine similarity matching** . for notifications without a channel match, compute the dot product against every existing topic's members. If the best score is above `ASSIGN_THRESHOLD`, the notification joins that topic. If not, a new topic is created.

3. **Headline and summary** . each new topic gets a provisional headline and summary from the notifications themselves. These get replaced by AI-generated versions in the next step.

Topics are scoped to a **day window** from 2 AM to 2 AM. A daily reset clears topics at 2 AM so each day starts fresh.

> **Deep dive:** [Embeddings & clustering](embeddings-and-clustering.html) details the `TopicClusteringPolicy`, how JSON arrays of notification IDs are maintained, and the full topic lifecycle.

---

## 7. Narrative generation

A topic with just raw notifications and a provisional headline isn't very readable. The last step is narrative generation . writing a concise, human-readable summary.

`TopicNarrativeProcessor` kicks in after classification and clustering are done. Each **dirty** topic (one with new notifications or modified content) gets processed:

The model is given `GenerateTopicCardTool` with **automatic tool calling enabled**. The tool definition asks for:
- `title` . a short, scannable headline
- `summary` . 1–3 sentences about what happened
- `actions` . suggested quick actions like "Open Chat" or "View in payment app"

The model sees all notifications in the topic, along with context about which apps are involved and their package names (so it can suggest real launchable actions).

If the LLM isn't ready or the tool doesn't fire, a **fallback** concatenates notification text into a simple summary. Narrative generation runs at low priority . it gets preempted if new notifications arrive mid-generation.

> **Deep dive:** [LLM inference pipeline](inference-pipeline.html) explains why `NonCancellable` is necessary during narrative generation with automatic tool calling.

---

## 8. The widgets dashboard

Extracted data from step 4 doesn't just sit in the database. It powers **Pulse** . a customizable widgets dashboard.

`WidgetComputeEngine` takes raw extracted data and runs aggregation operations:

| Operation | What it does | Example result |
|---|---|---|
| `SUM` | Totals a numeric field | "₹1,250" (total spending today) |
| `COUNT` | Counts distinct items | "5 people reached out" |
| `LATEST` | Shows the most recent entry | "A contact: Call me when free" |
| `LIST` | Groups by a field and shows top groups | "3 orders · most active merchant" |
| `MAX` | Finds the maximum value | "₹5,000 · Bank transaction" |
| `STATUS` | Groups by status field | "2 shipped, 1 delivered, 1 delayed" |

Finance widgets get special treatment . they merge bank transactions with extracted finance data for a complete picture of spending.

Users create widgets through the **Pulse Wizard**, choosing:
- A question (e.g., "How much did I spend?")
- A category (finance, work, personal, logistics)
- An operation (sum, count, latest, etc.)
- Optional app filters

Widgets update every time the inference worker runs.

> **Deep dive:** [Extraction & widgets](extraction-and-widgets.html) covers the full `WidgetComputeEngine`, Pulse wizard flow, and the finance transaction pipeline.

---

## 9. Keeping it all running

Android aggressively kills background processes. Focal survives through several mechanisms:

- **Foreground service** (`LlmForegroundService`) . a persistent notification keeps the process alive when the LLM is loaded
- **Battery optimization exemption** . requested on first launch and available in settings
- **Boot receiver** (`BootReceiver`) . restarts the service after device reboot
- **WorkManager** (`InferenceWorker`) . schedules inference work reliably, respecting Doze mode
- **Priority-based queuing** (`InferenceWorkQueue`) . classification (HIGH) always runs before narrative generation (LOW)

The worker processes work in priority order within a single run. When high-priority classification work arrives mid-narrative, the narrative task re-enqueues itself and yields.

> **Deep dive:** [Architecture & internals](architecture.html) covers the service lifecycle, WorkManager integration, and all background components. [LLM inference pipeline](inference-pipeline.html) details the queuing system.

---

## 10. The full picture

Here's the complete pipeline in one view:

```
Notification arrives
    │
    ▼
NotificationListener extracts raw data
    │
    ▼
Stored in Room (category = "uncategorized")
    │
    ▼
RulesEngine checks keyword/sender/app rules
    │
    ├── Rule matches "matters" → done
    ├── Rule matches "noise"   → done
    └── Rule is "auto" or none → continue
    │
    ▼
InferenceWorker wakes up (WorkManager)
    │
    ▼
Classifier runs batch classification via LLM tool calls
    │  (with automatic tool calling disabled)
    │
    ├── Extraction tools called for finance/work/personal/logistics
    └── Bank SMS detected and transactions extracted
    │
    ▼
Matters notifications go to TopicEngine
    │
    ├── Unembedded? → SentencePiece tokenize → EmbeddingGemma infer → L2 normalize
    ├── Channel match? → assign to same topic
    └── Cosine similarity → assign or create new topic
    │
    ▼
TopicNarrativeProcessor generates headlines and summaries
    │  (with automatic tool calling enabled)
    │
    ▼
WidgetComputeEngine computes Pulse widget values
    │
    ▼
Digest screen shows topics · Pulse screen shows widgets
```

Every step runs on-device. Every step is verifiable through Room data, logcat output, and the UI itself.

---

## What to read next

- [Architecture & internals](architecture.html) . database schema, DI wiring, all the plumbing
- [Classification & rules](classification-and-rules.html) . how each notification is triaged
- [LLM inference pipeline](inference-pipeline.html) . model invocation, streaming, worker lifecycle
- [Embeddings & clustering](embeddings-and-clustering.html) . JNI bridge, vector math, topic engine
- [Extraction & widgets](extraction-and-widgets.html) . tool-based data extraction and Pulse dashboard
- [Technology decisions](tech-decisions.html) . why we made the choices we made
- [Fine-tuning guide](finetuning-guide.html) . how Gemma 4 E2B was fine-tuned for notification triage
