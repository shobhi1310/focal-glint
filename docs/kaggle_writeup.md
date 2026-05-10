# Focal: On-Device Notification Intelligence with Gemma 4

**A privacy-first Android app that classifies, clusters, and summarises notifications using Gemma 4 E2B running entirely on the device GPU.**

---

## The Problem

A typical smartphone user receives 80 to 100 notifications per day across work, finance, messaging, delivery, social, and utility apps. These arrive at the same priority with no awareness of each other. The user either checks everything manually or misses things by ignoring the feed. Focal addresses this by running an on-device intelligence pipeline that determines what matters, groups related signals, and surfaces a structured digest without any data leaving the device.

---

## Architecture Overview

Focal processes notifications through four sequential layers: deterministic rules, LLM-based classification, structured extraction, and narrative generation. The LLM is only invoked where rule-based handling is insufficient. This keeps inference load minimal and latency acceptable for a background service.

---

## Notification Preprocessing

When a notification arrives via `NotificationListenerService`, Focal extracts and normalises the following fields before any further processing: app package name, app display name, notification title, content text, `bigText` (expanded body when available), posting timestamp, and notification channel ID.

A `BankSmsDetector` runs a pattern-match pass at this point. If the notification matches known Indian bank SMS formats, it is flagged immediately. This flag is carried through the pipeline and routes the notification to a dedicated `extractBankTransaction` tool during the extraction phase, bypassing general classification entirely.

Processed notifications are persisted to a local Room database. A `DayWindow` utility defines the start and end of the current processing window so that only today's notifications are included in classification and digest generation.

---

## Priority Queue and Worker Architecture

All background inference runs through Android WorkManager. Tasks are enqueued with explicit priority ordering:

1. Classification of newly arrived notifications
2. Structured data extraction for classified "matters" notifications
3. Narrative regeneration for updated topic clusters
4. Embedding generation for semantic clustering

This ordering ensures fresh notifications are classified promptly even when the system is also rebuilding narrative content. The LLM engine is accessed through a `Mutex`. UI-triggered inference calls use `tryLock()` and fail fast rather than blocking behind a running background batch. All LiteRT inference runs on a dedicated single-thread dispatcher to satisfy LiteRT-LM's one-session-at-a-time constraint.

---

## Layer 1: Rules Engine

Notifications pass through a three-tier rules engine before reaching the LLM.

**Tier 1 — User overrides.** Through the Tune screen, users assign per-app behaviour: "always matters," "always noise," or "auto." Auto is the only path that continues to the LLM. User rules have the highest priority in the system.

**Tier 2 — System defaults.** A set of static app-level rules covers common high-confidence cases. Ride-hailing apps (Rapido, Ola, Uber), e-commerce platforms (Flipkart, Amazon, Myntra), and ad services are pre-classified as noise. Messaging apps (WhatsApp, Telegram, Slack), food delivery (Swiggy, Zomato), and financial apps (CRED) are pre-classified as matters. These rules handle a large share of daily volume with zero inference cost.

**Tier 3 — LLM-learned rules.** The system accumulates classification patterns over time and applies them as a third tier before falling through to live inference.

---

## Layer 2: LLM Classification with Gemma 4

Notifications that reach the LLM are batched into a numbered list and sent in a single inference call. Gemma 4 E2B calls a structured `classifyNotification(index, category, reason)` tool once per index. Category is constrained to "matters" or "noise."

**User focus injection.** The Tune screen lets the user write a natural language statement describing what they care about. This is injected into the classification system prompt, replacing the default criteria. The model evaluates every notification against the user's stated priorities.

**Retry logic.** Small models skip indices on longer batches. A `BatchConversationRunner` checks coverage after each pass and issues targeted retry turns for any missed indices. This uses `automaticToolCalling = false` so each tool call is visible to the application as it arrives, enabling per-index tracking.

**Prompt separation.** The extraction system prompt lists category names like "work" and "personal" for the extraction tools. Early iterations caused the model to use these as classification categories. The fix was explicit phase separation language and constraint repetition in four prompt locations.

---

## Layer 3: Structured Extraction

A second inference pass processes "matters" notifications through category-specific tools. Each tool captures typed fields directly into the Room database.

Financial data uses a fully separate storage path. Bank SMS notifications flagged by the detector go to `extractBankTransaction` (amount, direction, masked account, bank, merchant). App-level finance notifications go to `extractFinance`. Both write to a dedicated `TransactionEntity` table, never to the general `ExtractedDataEntity` table used by other categories. The Finance widget reads `TransactionEntity` directly. This separation keeps financial aggregation simple and accurate.

Work extractions capture the specific entity being acted on (PR, build, issue), the triggering person, and the action type. Personal extractions capture sender name and channel. Logistics extractions capture item, merchant, status, and ETA.

Each Pulse widget can be scoped to specific source apps via an app filter stored in `WidgetConfigEntity`. Filtering is applied at the repository layer before any computation.

The `CloudClassifier` mirrors this entire extraction pipeline for cloud mode, writing results into the same schema. From the widget computation layer upward, the data source is opaque.

---

## Layer 4: Semantic Clustering and Narrative Generation

Notifications are encoded using the Gemma Embedding model (embeddinggemma-300M, sequence length 1024, mixed precision) running via LiteRT. The embedding session runs independently of the LLM session to avoid competing for LiteRT's single-session constraint. Clusters are formed using cosine similarity, with channel-aware grouping applied first for same-conversation notifications.

For each cluster, Gemma 4 generates a topic card using a `generateTopicCard` tool with `automaticToolCalling = true`. The prompt injects a numbered `AVAILABLE APPS` list; the model outputs integer indices for actions, which the app resolves to package names at call time. This eliminates hallucinated package names.

---

## Fine-Tuning with Unsloth and the Conversion Gap

To improve accuracy on Indian notification patterns (regional app formats, mixed-language content, local bank SMS structures), we fine-tuned a Gemma 4 variant using Unsloth on a labeled notification dataset. Training produced a merged safetensor checkpoint with measurable improvement on our evaluation split.

Deploying the fine-tuned model on-device required converting the merged checkpoint to a LiteRT-compatible format. This conversion path for fine-tuned Gemma 4 variants is not documented in Google's AI Edge repositories or any available community resource. The tooling exists in fragments but no verified end-to-end pipeline was available for a custom merged model. We could not take the risk of an unverified conversion on a competition timeline.

---

## Cloud Classification via llama.cpp

With the on-device fine-tuning path blocked, we built a second inference backend: a self-hosted model served through llama.cpp. Notification batches are routed to the endpoint, which runs the same tool-calling classification and extraction logic and returns structured results in the same format as the on-device path. Results are written into the same Room database. The fine-tuned model is deployed server-side, bypassing the LiteRT conversion requirement.

Cloud mode is opt-in. On-device inference is the default. Users who enable cloud mode understand that notification content leaves the device.

---

## What We Learned

The rules layer handles more volume than anticipated and should be built first, not as an afterthought. The LLM adds value specifically in ambiguous cases that rules cannot cover cleanly.

The LiteRT conversion pipeline for custom fine-tuned Gemma variants is a real documentation gap. Anyone building production on-device experiences with fine-tuned models will hit this. A clear, verified path from safetensor to LiteRT task file for Gemma 4 would significantly lower the barrier.

Keeping financial data in a separate table from the start made every downstream operation simpler. Categories with structurally different semantics benefit from separate storage.

Batch classification needs a retry layer to be production-reliable. This is infrastructure, not an edge case handler.

---

## Conclusion

Focal demonstrates that on-device LLM inference is viable as a production background service when paired with a well-designed preprocessing and prioritisation stack. Gemma 4 E2B handles classification and narrative generation. The Gemma Embedding model handles semantic clustering. Deterministic rules and structured preprocessing reduce inference load to what actually requires reasoning. The llama.cpp cloud path adds resilience and enables fine-tuned model deployment where the on-device conversion path is not yet accessible.
