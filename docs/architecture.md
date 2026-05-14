---
title: Architecture & internals
description: Database schema, dependency injection, background services, and how Focal is wired together
---

# Architecture & internals

This page covers the internal plumbing. Read [How Focal works](index.html) first if you haven't already — this page assumes you know the overall pipeline.

---

## Database schema (Room, version 9)

Focal uses Room with 9 tables and 9 DAOs. The database has been through 8 migrations.

### `notifications` table

The core table. Every intercepted notification lands here.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT (PK) | UUID |
| `package_name` | TEXT | App package name |
| `app_name` | TEXT | Human-readable app name |
| `title` | TEXT | Notification title |
| `content` | TEXT | Notification body |
| `big_text` | TEXT | Expanded notification text |
| `notification_key` | TEXT | Android notification channel key |
| `category` | TEXT | `matters`, `noise`, or `uncategorized` |
| `classified_by` | TEXT | `rule`, `llm`, `pending`, or `user` |
| `processed_at` | INTEGER | Timestamp when classification completed |
| `posted_at` | INTEGER | When the notification was posted |
| `conversation` | TEXT | Conversation name (messaging apps) |
| `content_hash` | INTEGER | Hash for duplicate detection |
| `embedding` | BLOB | 768-dim float array as raw bytes |
| `is_summary` | INTEGER | 1 if this is a generated summary |
| `summary_text` | TEXT | AI-generated summary text |
| `is_bank_transaction` | INTEGER | 1 if detected as bank SMS |
| `processed_for_topics` | INTEGER | 1 if topic assignment done |

**Indices on:** `[package_name, posted_at]`, `[category, posted_at]`, `[processed_at]`, `[notification_key]`

### `topics` table

Groups of notifications clustered by semantic similarity.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT (PK) | UUID |
| `headline` | TEXT | Topic title |
| `summary` | TEXT | Topic summary |
| `category` | TEXT | Always `matters` |
| `notification_ids` | TEXT | JSON array of notification UUIDs |
| `source_apps` | TEXT | JSON array of app names |
| `channel_count` | INTEGER | Distinct app count |
| `detail_json` | TEXT | JSON blob for structured detail |
| `action_label` | TEXT | Action button text |
| `action_package` | TEXT | Package for deep-link action |
| `needs_narrative_regen` | INTEGER | 1 if LLM summary is stale |
| `updated_at` | INTEGER | Last modification timestamp |
| `briefing_contribution` | TEXT | Contribution text for daily briefing |
| `actions_json` | TEXT | JSON array of SuggestedAction objects |

### `rules` table

User-defined classification rules.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER (PK) | Auto-increment |
| `type` | TEXT | `keyword_match`, `sender_match`, or `app_match` |
| `pattern` | TEXT | Keyword or sender pattern |
| `app` | TEXT | Package name (for sender/app match) |
| `category` | TEXT | `matters`, `noise`, or `auto` |
| `confidence` | REAL | 0.0–1.0 |
| `hit_count` | INTEGER | How often this rule matched |

### `corrections` table

Tracks when users override an AI classification. Used to learn user preferences.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER (PK) | Auto-increment |
| `package_name` | TEXT | App package name |
| `correction_category` | TEXT | What the user changed it to |
| `created_at` | INTEGER | Timestamp |

### `app_profiles` table

Aggregated stats per app, including detected app type.

| Column | Type | Notes |
|---|---|---|
| `package_name` | TEXT (PK) | App package name |
| `app_name` | TEXT | Display name |
| `app_type` | TEXT | `messaging`, `transactional`, `promotional`, or `other` |
| `notification_count` | INTEGER | Total notifications seen |
| `last_seen_at` | INTEGER | Most recent notification timestamp |

### `widget_configs` table

User-created Pulse widget definitions.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT (PK) | UUID |
| `title` | TEXT | User-facing name |
| `category` | TEXT | `finance`, `work`, `personal`, `logistics` |
| `operation` | TEXT | `SUM`, `COUNT`, `LATEST`, `LIST`, `MAX`, `STATUS` |
| `field` | TEXT | Which extracted field to operate on |
| `group_by` | TEXT | Field to group results by |
| `filter_apps` | TEXT | JSON array of package names to filter |
| `question` | TEXT | User's natural language question |

### `widget_state` table

Computed widget values (regenerated every worker cycle).

| Column | Type | Notes |
|---|---|---|
| `widget_id` | TEXT (PK) | FK → widget_configs |
| `headline` | TEXT | Main value display |
| `subtitle` | TEXT | Secondary text |
| `badge` | TEXT | Badge/chip text |
| `detail_json` | TEXT | JSON for drill-down view |
| `source_app_icons` | TEXT | JSON of package names for icon row |
| `item_count` | INTEGER | Number of items |
| `last_updated_at` | INTEGER | Timestamp |

### `extracted_data` table

Structured data extracted by the LLM's extraction tools.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER (PK) | Auto-increment |
| `notification_id` | TEXT | FK → notifications |
| `category` | TEXT | `finance`, `work`, `personal`, `logistics`, `bank_transaction` |
| `data` | TEXT | JSON blob with extracted fields |
| `app_package` | TEXT | Source app package |
| `extracted_at` | INTEGER | Timestamp |

### `transactions` table

Financial transactions from bank SMS.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER (PK) | Auto-increment |
| `notification_id` | TEXT | FK → notifications |
| `amount` | REAL | Transaction amount |
| `direction` | TEXT | `debit` or `credit` |
| `account` | TEXT | Masked account number |
| `bank` | TEXT | Bank name |
| `raw_merchant` | TEXT | Merchant from SMS |
| `matched_merchant` | TEXT | Normalized merchant name |
| `matched_app` | TEXT | App the merchant maps to |
| `posted_at` | INTEGER | Timestamp |

---

## Dependency injection (Dagger Hilt)

Focal uses Hilt for DI. The key modules are:

### `DatabaseModule`

Provides the singleton `FocalDatabase` instance and all 9 DAOs:

```kotlin
@Singleton
@Provides
fun provideDatabase(@ApplicationContext context: Context): FocalDatabase

@Provides
fun provideNotificationDao(db: FocalDatabase): NotificationDao
// ... 8 more DAO providers following the same pattern
```

### `RepositoryModule`

Binds repository implementations to their interfaces. The repositories are thin abstractions over DAOs, offering suspend functions tailored to business logic:

- `NotificationRepository` — queries for unclassified, unembedded, unprocessed notifications; bulk classification updates; embedding storage; app profile management
- `TopicRepository` — CRUD, active topics in window, dirty topic queries, member updates
- `RuleRepository` — CRUD, priority-ordered rule listing, hit count incrementing
- `TransactionRepository` — insert, query by notification ID, get all
- `WidgetRepository` — config CRUD, state management, extracted data queries, active category detection

### `IntelligenceModule`

Provides the intelligence layer singletons:

- `InferenceProvider` (singleton) — the LLM inference engine
- `Classifier` — notification classifier
- `RulesEngine` — deterministic rule-based classification
- `TopicEngine` — notification clustering
- `TopicNarrativeProcessor` — LLM-powered narrative generation
- `EmbeddingProvider` / `SwitchableEmbeddingProvider` — embedding model access
- `WidgetComputeEngine` — widget value computation
- `TransactionCorrelator` — merchant-to-app matching

---

## Singleton architecture

Focal uses a strict singleton pattern for all inference-related components. This is necessary because:

1. **LLM inference is stateful** — the Gemma model holds a KV cache in GPU/CPU memory. Loading two instances would require 2× RAM.

2. **Embedding model is stateful** — `SentencePieceTokenizer` and `EmbeddingGemmaLiteRtEmbedder` each hold native heap-allocated objects pointed to by `Long` handles. Two instances would duplicate native allocations.

3. **Work queue must be global** — `InferenceWorkQueue` deduplicates work types. Two queues would defeat deduplication.

```kotlin
@Singleton
class InferenceWorkQueue @Inject constructor() { ... }

@Singleton
class TopicNarrativeProcessor @Inject constructor(...) { ... }

@Singleton
class WidgetComputeEngine @Inject constructor(...) { ... }
```

The `InferenceProvider` is a singleton created by `LiteRtLmProviderFactory` in `IntelligenceModule`. It's injected everywhere that needs LLM access.

---

## Foreground service (`LlmForegroundService`)

When the LLM model is loaded into memory, the system must not kill the process. Focal runs a foreground service to prevent this.

```kotlin
@AndroidEntryPoint
class LlmForegroundService : Service()
```

Key behaviors:

- Posts a **silent, minimum-priority notification** with title "Focal AI" and body "Processing notifications in the background"
- Uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on Android 14+
- Checks if the engine is enabled on every `onStartCommand` — stops self if disabled
- Handles pending topic rebuilds: if the engine is warm and a rebuild was queued, it enqueues a new `InferenceWorker` run
- Returns `START_NOT_STICKY` — won't be restarted by the system if killed

The service is started in three places:
1. `MainActivity.warmEngines()` — when the app opens and models load
2. `InferenceWorker.doWork()` — at the start of every inference cycle
3. `BootReceiver` — after device reboot

---

## WorkManager integration

`InferenceWorker` is a `CoroutineWorker` registered as a unique work chain with name `"focal_inference"`. It uses `ExistingWorkPolicy.REPLACE` so only one instance runs at a time.

The worker is triggered by:
1. `FocalNotificationListener` — when new notifications arrive
2. `LlmForegroundService` — on pending rebuild
3. `DailyResetWorker` — at 2 AM daily reset

---

## Background components summary

| Component | Type | Purpose |
|---|---|---|
| `FocalNotificationListener` | `NotificationListenerService` | Intercepts all notifications |
| `LlmForegroundService` | `Foreground Service` | Keeps process alive when LLM loaded |
| `InferenceWorker` | `CoroutineWorker` | Runs the classification/clustering/summary pipeline |
| `InferenceWorkQueue` | `@Singleton` | Priority queue for inference tasks |
| `DailyResetWorker` | `CoroutineWorker` | Resets topics at 2 AM |
| `BootReceiver` | `BroadcastReceiver` | Restarts service after reboot |
| `DownloadManager` | System service | Downloads model files |

---

## What to read next

- [Classification & rules](classification-and-rules.html) — how each notification is triaged
- [LLM inference pipeline](inference-pipeline.html) — model invocation, streaming, worker lifecycle
- [Embeddings & clustering](embeddings-and-clustering.html) — JNI bridge, vector math, topic engine
- [Extraction & widgets](extraction-and-widgets.html) — tool-based data extraction and Pulse dashboard
- [Technology decisions](tech-decisions.html) — why we made the choices we made
