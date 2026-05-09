# Focal — On-Device Notification Intelligence

**A technical architecture document from the perspective of a senior staff engineer evaluating system design, runtime behavior, privacy guarantees, and scaling constraints.**

---

## Executive Summary

Focal is an Android notification triage application that runs a 2.6B parameter LLM (Gemma 4 E2B) entirely on-device to classify, cluster, and narrate the user's notification stream into an actionable daily digest. The system operates with zero network dependency for core intelligence — every notification is processed locally using LiteRT-LM for inference and a custom 300M parameter embedding model for semantic clustering.

The architecture enforces a strict privacy contract: notification content never leaves the device unless the user explicitly opts into cloud classification. There is no telemetry, no server-side logging of notification text, and no data pipeline that touches user content without explicit consent.

---

## 1. Why LiteRT-LM

LiteRT-LM (Google's on-device LLM runtime for Android) was chosen over alternatives for specific engineering reasons:

**vs. MediaPipe LLM Inference API:** MediaPipe provides a simpler abstraction but lacks native tool-calling support. Focal's entire classification pipeline depends on structured tool calls (`classifyNotification`, `extractFinance`, `extractBankTransaction`) — the model must emit JSON-structured function calls, not free-form text. LiteRT-LM provides `ToolSet`, `@Tool`, `@ToolParam` annotations, and automatic tool dispatch natively.

**vs. ONNX Runtime / llama.cpp:** These require JNI wrappers, manual KV cache management, and custom tokenizer integration. LiteRT-LM ships as a single AAR with the full inference stack — model loading, KV cache lifecycle, GPU/CPU backend switching, conversation state, and token streaming.

**vs. Cloud-only:** Defeats the privacy guarantee. The model must run where the data lives.

**Key capabilities leveraged:**
- Automatic tool calling with `automaticToolCalling = true` — the framework routes tool invocations without manual parsing
- Thinking channel support (`<|channel>thought...`) for reasoning without polluting output
- Conversation sessions with persistent KV state across multi-turn interactions
- GPU backend with CPU fallback — automatic recovery on SIGSEGV
- Single model file format (`.litertlm`) containing weights, tokenizer config, and inference graph

---

## 2. The Model: Gemma 4 E2B (2.6B effective parameters)

The model is a MatFormer variant of Gemma — E2B means "effective 2 billion" using nested sub-architectures that share weight slices. This gives approximately 2.6B effective parameters at inference time while occupying ~2.58GB on disk.

**Why this model size:** On a device with 6-8GB RAM (Redmi Note 10 Pro, OnePlus CPH2793), a 2.6B model leaves adequate headroom for the OS, foreground apps, and the embedding model. A 7B model would require 4x swaps or memory-mapped inference, introducing latency spikes that break the batch processing budget.

**Context window:** Configurable 4K-8K tokens via a user-facing multiplier. Default 8K. Each classification batch of 10 notifications consumes ~200-400 tokens of input, leaving ample room for tool call output.

**Instruction tuned:** The `-it` suffix indicates instruction-following fine-tuning, critical for reliable tool call compliance. The model consistently emits structured `classifyNotification(index, category, reason)` calls rather than prose.

---

## 3. Inference Pipeline: Single Thread, Priority Queue, Multi-Pass

### The KV Cache Constraint

There is exactly one LLM session active at any time. LiteRT-LM does not support parallel decoding or multiple conversation contexts. This is the fundamental constraint that shapes the entire processing architecture.

The `InferenceProvider` enforces this with a `Mutex`:
```
inferenceProvider.generateWithTools(...)  // Acquires mutex
// ... model generates tokens ...
// Mutex released on completion or error
```

All classification, extraction, and narrative generation compete for this single inference slot.

### Priority Work Queue

The `InferenceWorkQueue` uses a `PriorityBlockingQueue` with two tiers:

| Priority | Work Type | Latency Target |
|----------|-----------|---------------|
| HIGH (0) | `CLASSIFY_PENDING` | <30s from notification arrival |
| LOW (1) | `GENERATE_NARRATIVES` | Best-effort, no SLA |

**Preemption:** After completing each LOW-priority item, the worker checks `hasHighPriority()`. If a new notification arrived during narrative generation, classification takes precedence on the next loop iteration.

**Deduplication:** The queue prevents enqueueing the same work type twice. If `CLASSIFY_PENDING` is already queued and a new notification arrives, the existing queue entry covers it (the classification pass queries the DB for all pending notifications, not a fixed set).

### The Complete InferenceWorker Pass

A single `InferenceWorker.doWork()` execution performs:

```
1. CLASSIFY_PENDING (HIGH priority)
   ├── Re-apply rules to all recent notifications (catches config changes)
   ├── Get pending + bank-flagged notifications needing extraction
   ├── Batch into groups of 10
   └── For each batch: single LLM call handles:
       ├── classifyNotification × N (matters/noise for each)
       ├── extractFinance (optional, LLM decides)
       └── extractBankTransaction (mandatory for bank SMS)
   ├── Save classifications to DB
   ├── Save bank transactions from extraction results
   └── Run topic generation:
       ├── Embed unembedded notifications (EmbeddingGemma, no LLM)
       ├── Assign notifications to topics (cosine similarity, no LLM)
       ├── Correlate bank transactions to app extractions (pure code)
       └── Compute widget states (pure code)

2. GENERATE_NARRATIVES (LOW priority)
   └── For each dirty topic:
       ├── Build prompt with member notifications
       ├── Single LLM call → generateTopicCard(title, summary, actions)
       └── Save headline + summary + suggested actions to DB
```

The key insight: steps 1's topic generation, correlation, and widget compute are **LLM-free** — they use embeddings (separate model) and pure code. Only the initial classification/extraction and the final narrative generation require the LLM.

---

## 4. The Embedding Model: EmbeddingGemma (300M)

**Model:** `embeddinggemma-300M_seq1024_mixed-precision.tflite` — a purpose-built 300M parameter model from Google optimized for semantic similarity on edge devices.

**Why a separate model (not reusing Gemma 4 E2B):**
- Latency: Embedding takes ~20ms per notification vs ~300-500ms for a full forward pass through 2.6B params
- Contention: Embeddings run on a separate TFLite runtime, not the LiteRT-LM engine — no KV cache competition
- Quality: EmbeddingGemma is trained with a contrastive objective specifically for similarity, outperforming zero-shot decoder embeddings

**Backend policy:** CPU-only for embeddings. Rationale: GPU inference for the LLM uses `GL_COMPUTE` shaders. Running embeddings on GPU simultaneously would contend for the same render queue, causing frame drops and potential ANRs. The 300M model is fast enough on CPU (sub-22ms per embedding on Snapdragon 778G+).

**Usage in TopicEngine:**
1. Format notification text via `EmbeddingTextFormatter` (app name + title + body, truncated to 1024 tokens)
2. Tokenize with SentencePiece (same tokenizer family as Gemma)
3. Produce 768-dimensional L2-normalized vector
4. Store as BLOB in notifications table
5. Cluster via cosine similarity: `dot(vec_a, vec_b)` ≥ 0.93 threshold → same topic

**Channel-first optimization:** Before computing cosine similarity, the engine checks if the new notification's `notification_key` matches any existing topic member's key. Same-channel notifications (e.g., multiple WhatsApp messages from the same contact) are assigned instantly — no embedding comparison needed.

---

## 5. Rules Engine: Fast Deterministic Gate

Before the LLM ever sees a notification, the `RulesEngine` applies deterministic pattern matching:

```
Notification arrives
    ↓
keyword_match rules (highest priority, content-based)
    ↓ no match
sender_match rules (app + title pattern)
    ↓ no match
app_match rules (package name only, lowest priority)
    ↓ no match
Falls through to LLM classification
```

**Priority ordering:** User rules (`user_explicit`) are checked before LLM-extracted rules, which are checked before system defaults. This ensures user intent always wins.

**The "auto" override:** When a user taps "A" on an app that has a system default rule, a `user_explicit` rule with `category = "auto"` is created. The RulesEngine treats `"auto"` as a signal to skip — returns null, forcing the notification to the LLM. This allows users to override system opinions without deleting the rule.

**29 default rules** cover the most common Indian app ecosystem: ride-hailing (Rapido, Uber, Ola → noise), messaging (WhatsApp, Telegram, Slack → matters), payments (Paytm, PhonePe → noise for promos), and logistics (Swiggy, Zomato → matters).

---

## 6. Room Database: Why SQLite

**Choice rationale:**
- Jetpack Room provides compile-time SQL verification, migration support, and Kotlin coroutine-native DAO interfaces
- Notifications arrive at high frequency (100+ per day) with structured relational data — SQL handles this cleanly
- Embedding vectors stored as BLOBs with indexed notification_id for O(1) lookup
- Widget state computed from JOINs across extracted_data and transactions tables
- Migration system (v1→v9) handles schema evolution without data loss on app updates

**9 entities** model the complete data lifecycle: raw notification → classification → embedding → topic clustering → narrative generation → widget aggregation → transaction correlation.

**Key design decisions:**
- Embeddings stored alongside notifications (not a separate vector DB) — avoids sync complexity, leverages Room's transactional guarantees
- Widget state is a materialized view (computed and cached, not queried live) — avoids expensive aggregation on UI thread
- `content_hash` column enables deduplication without full-text comparison
- `notification_key` index enables O(1) channel-matching in TopicEngine

---

## 7. Kotlin for Android Development

**Why Kotlin (not Java):**
- Coroutine-native: The entire inference pipeline is `suspend fun` based. No callback hell, no thread pool management. `Mutex`, `Flow`, `Channel` provide structured concurrency.
- Data classes: `NotificationEntity`, `TransactionEntity`, `ClassificationResult` — all immutable with copy semantics, perfect for Room entities and state management.
- Extension functions: `VectorMath.dot()`, `ThinkingMode.stripThoughtBlocks()` — clean utility APIs without static helper classes.
- Sealed classes: `WorkType`, `WorkPriority`, `PulseDetailEvent` — exhaustive `when` matching ensures no unhandled cases.
- Jetpack Compose: Kotlin-only UI framework. No XML layouts, no ViewBinding, no Fragment lifecycle nightmares.

**Compose UI architecture:** Unidirectional data flow (ViewModel → StateFlow → Composable). No mutable state in composables beyond local UI ephemeral state (scroll position, animation state).

---

## 8. Cloud Inference Path

When the user enables cloud classification in Settings, the system routes to a hosted Gemma instance:

**API contract:** OpenAI-compatible `/v1/chat/completions` endpoint. The same tool definitions (classifyNotification, extractFinance, etc.) work identically — the cloud model is the same Gemma family, just running on better hardware.

**When it matters:**
- Users with low-end devices (4GB RAM) where on-device inference is too slow
- During model download (first-time setup) — cloud handles classification until local model is ready
- A/B testing classification quality between local and cloud

**Privacy controls:**
- Explicit opt-in toggle in Tune screen
- `X-Focal-Consent` header communicates data usage agreement
- Separate "Help improve Focal" toggle for training data consent
- Cloud endpoint is configurable (can point to self-hosted instance)

**Architectural separation:** `Classifier` is the routing layer. It checks `modelManager.isCloudEnabled()` and delegates to either `inferenceProvider.generateWithTools()` (local) or `cloudClassifier.classifyAndExtractBatch()` (remote). The rest of the pipeline (topic engine, widget compute, correlation) is always local.

---

## 9. Pulse Widgets: Aggregation Without Polling

Widgets are computed, not polled. There is no background timer recomputing widget state — computation happens exclusively as a step in `InferenceWorker`, triggered by:
- New notification classified
- User pull-to-refresh
- Rule change (3s debounced)

**Widget compute flow:**
```
WidgetComputeEngine.computeAll()
  → For each WidgetConfigEntity:
      → finance? → Read from transactions table (bank SMS = truth)
      → others?  → Read from extracted_data by category
      → Apply operation (SUM, COUNT, MAX, LIST, STATUS)
      → Produce WidgetStateEntity (headline, badge, subtitle, detailJson)
      → Upsert to widget_state table
  → Room Flow triggers UI recomposition
```

**Finance widget optimization:** Bank SMS are the source of truth for money movement. The `TransactionCorrelator` (pure code, no LLM) matches bank-confirmed amounts to app notifications by exact amount + direction, using time proximity as tiebreaker for duplicates. Unmatched transactions appear as "Unassigned" — real money moved, no app trail (card swipes, ATM, net banking).

---

## 10. Privacy Architecture

**On-device processing guarantees:**
- `NotificationListenerService` captures raw notification content — this data never leaves the process unless cloud inference is explicitly enabled
- Embedding vectors are derived locally and stored only in the app's private SQLite database
- LLM inference runs in-process via LiteRT-LM — no IPC, no shared memory, no content providers
- The foreground service notification shows "Processing notifications in the background" — it does not expose content

**Network isolation:**
- `INTERNET` permission exists solely for: (a) model download from HuggingFace, (b) optional cloud inference
- No analytics SDK, no crash reporting that captures notification content, no Firebase Cloud Messaging for data sync
- The only network call in normal operation is the optional cloud classifier — and that's behind two explicit toggles

**User focus prompt:**
- The "YOUR FOCUS" text is stored in local SharedPreferences, never transmitted
- It modifies the system prompt for the local LLM — the model sees it, but it stays on-device

**Data lifecycle:**
- Notifications older than 24 hours are excluded from the active digest window
- `DailyResetWorker` runs at 2 AM to clean stale state
- No notification content is ever written to external storage or shared with other apps

---

## 11. Key Optimizations

| Optimization | Problem Solved | Impact |
|-------------|---------------|--------|
| Priority queue with preemption | Narrative generation (60s+) blocking new notification classification | Classification latency: <30s from arrival |
| Batched classification (10 per LLM call) | Per-notification LLM calls too expensive (5s each × 100 = 500s) | 100 notifications classified in ~5 batches = 25-30s |
| Channel-first topic matching | Cosine similarity O(N×M) for same-contact messages | Same-channel messages assigned in O(1) without embedding |
| CPU-only embeddings | GPU contention with LLM causing frame drops | Zero UI jank, embeddings still <22ms |
| Widget state materialization | Expensive aggregation queries on UI thread | UI reads pre-computed state; no JOINs at render time |
| Rule engine before LLM | LLM not needed for obvious cases (WhatsApp = matters always) | ~60% of notifications classified instantly, no LLM cost |
| Deterministic bank SMS gate | Prevent LLM calls for OTP/login SMS | Zero false positives on non-transaction SMS |
| Redacted SMS re-extraction on unlock | Lock screen hides SMS content from listener | Recovers transaction data once phone is unlocked |
| Single LLM pass for classify + extract | Separate passes would double KV cache contention | One inference call handles both classification and structured extraction |

---

## 12. Future Scope

**Short-term (next milestone):**
- On-device LoRA fine-tuning using user corrections — the `CorrectionEntity` table already captures feedback, just needs a training loop
- Widget creation via natural language ("show me my Swiggy spending this week") leveraging the existing LLM + widget template system
- Cross-app story merging — Mom called (Phone) + Mom messaged (WhatsApp) + Mom's location shared = one unified story

**Medium-term (3-6 months):**
- Embedding model upgrade: replace EmbeddingGemma 300M with a distilled version of the main Gemma model (dual-purpose weights for both generation and embedding)
- Proactive notifications — "You have 3 unread messages from your manager spanning 2 hours" surfaced as a high-priority Focal notification
- Widget marketplace — community-shared widget configs (e.g., "Crypto portfolio tracker", "Gym check-in counter")

**Long-term (platform evolution):**
- Multi-device sync with end-to-end encrypted notification graph (phone + tablet + watch)
- On-device model personalization using federated learning across opted-in devices (aggregate gradient updates, never raw data)
- OS-level integration as a notification intelligence layer (Android API extension proposal)

---

## Appendix: System Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        ANDROID OS                                 │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │              NotificationListenerService                     │ │
│  │  FocalNotificationListener                                   │ │
│  │    ├── NotificationExtractor (title, content, bigText)       │ │
│  │    ├── BankSmsDetector (deterministic gate)                  │ │
│  │    ├── RulesEngine (instant classification)                  │ │
│  │    ├── Redacted SMS tracking + re-extraction on unlock       │ │
│  │    └── Enqueue InferenceWorker (30s batching delay)          │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              ↓                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │              InferenceWorker (WorkManager)                    │ │
│  │  Priority Queue: HIGH=classify → LOW=narratives              │ │
│  │                                                               │ │
│  │  ┌──────────────────────────────────────────────────────┐    │ │
│  │  │  LiteRT-LM Engine (Gemma 4 E2B, 2.6B params)        │    │ │
│  │  │    ├── classifyNotification × N (batch of 10)        │    │ │
│  │  │    ├── extractFinance (optional, LLM decides)        │    │ │
│  │  │    ├── extractBankTransaction (mandatory for SMS)    │    │ │
│  │  │    └── generateTopicCard (title + summary + actions) │    │ │
│  │  │  GPU backend ←→ CPU fallback                         │    │ │
│  │  │  Single KV cache, mutex-serialized                   │    │ │
│  │  └──────────────────────────────────────────────────────┘    │ │
│  │                                                               │ │
│  │  ┌──────────────────────────────────────────────────────┐    │ │
│  │  │  EmbeddingGemma (300M, TFLite, CPU-only)             │    │ │
│  │  │    └── 768-dim normalized vectors for clustering     │    │ │
│  │  └──────────────────────────────────────────────────────┘    │ │
│  │                                                               │ │
│  │  TopicEngine (cosine similarity + channel matching)           │ │
│  │  TransactionCorrelator (amount + direction matching)          │ │
│  │  WidgetComputeEngine (SUM/COUNT/MAX/LIST/STATUS)              │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              ↓                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │              Room Database (SQLite, v9)                       │ │
│  │    9 entities, 13 migrations, 7 DAOs                         │ │
│  │    Embeddings as BLOBs, widget state materialized            │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                              ↓                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │              Jetpack Compose UI                               │ │
│  │    Today (digest) │ Pulse (widgets) │ All │ Tune             │ │
│  │    StateFlow → collectAsStateWithLifecycle → recomposition   │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐ │
│  │  Optional: Cloud Classifier (explicit opt-in)                │ │
│  │  POST /v1/chat/completions → same tool schema                │ │
│  │  Headers: Authorization + X-Focal-Consent                    │ │
│  └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

*Document authored May 2026. Reflects codebase at commit `4b54fcb` on branch `dev-darahas`.*
