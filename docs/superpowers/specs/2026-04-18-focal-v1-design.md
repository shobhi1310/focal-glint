# Focal v1 — Design Specification

**Focus, Locally.** An on-device notification triage and semantic summarization engine for Android, powered by Gemma 3 1B.

**Target:** Shippable MVP in 4-6 weeks.
**Target Device:** Xiaomi Redmi Note 10 Pro (Snapdragon 720G, 6GB RAM, Android 13, MIUI 14).
**Approach:** LLM-First — validate on-device inference by week 3-4, with rules engine as fast fallback.

---

## 1. Problem Statement

Modern smartphone users receive hundreds of notifications daily from messaging apps, delivery services, promotional spam, and system events. This constant stream causes attention fragmentation, anxiety, and ADHD-like symptoms. Existing solutions either require cloud processing (privacy concern) or offer only basic do-not-disturb modes (insufficient intelligence).

Focal solves this by running a local LLM to semantically classify and summarize notifications into a structured Daily Digest — entirely on-device, with zero data leaving the phone.

## 2. Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                       ANDROID OS                              │
│                                                               │
│  ┌───────────────────────┐       ┌──────────────────────┐    │
│  │ NotificationListener  │       │   Focal App (UI)     │    │
│  │ Service (Background)  │       │   Jetpack Compose    │    │
│  │                       │       │                      │    │
│  │ Captures ALL notifs   │       │  Digest Screen       │    │
│  │ Extracts text, sender │       │  Apps Screen         │    │
│  │ Writes to Room DB     │       │  Settings Screen     │    │
│  └──────────┬────────────┘       └──────────┬───────────┘    │
│             │                                │               │
│             ▼                                │               │
│  ┌───────────────────────────────────────────┘               │
│  │                                                           │
│  │  ┌─────────────────────────────────────────────────┐     │
│  │  │              Room Database                       │     │
│  │  │  notifications | rules | corrections | app_prof  │     │
│  │  └──────────────────────┬──────────────────────────┘     │
│  │                         │                                 │
│  │  ┌──────────────────────┴──────────────────────────┐     │
│  │  │           Intelligence Engine                    │     │
│  │  │                                                  │     │
│  │  │  Layer 1: Rules Engine (microseconds)            │     │
│  │  │  Layer 2: LLM Classify + Summarize (3-8s each)  │     │
│  │  │  Layer 3: Rule Extraction (periodic, background) │     │
│  │  │                                                  │     │
│  │  │  InferenceProvider (Strategy Interface)           │     │
│  │  │  ├── LiteRtLmProvider (v1)                      │     │
│  │  │  └── MlcLlmProvider (future)                     │     │
│  │  └─────────────────────────────────────────────────┘     │
│  └───────────────────────────────────────────────────────────┘
└──────────────────────────────────────────────────────────────┘
```

**Data Flow:**

1. **Capture:** Notification arrives → `NotificationListenerService` extracts data → saves to Room DB
2. **Classify:** Layer 1 (rules) tries first → if no match → queued for Layer 2 (LLM batch)
3. **Summarize:** For chatty apps, LLM batches recent notifications into per-app summaries
4. **Present:** User opens app → Digest screen reads from DB → shows categorized view
5. **Learn:** User corrects a category → stored as correction → Layer 3 periodically extracts new rules

**Key Design Decisions:**

- **Room DB as the hub** — everything flows through the database. Listener writes, Engine reads/writes, UI reads. Simple, debuggable, survives process death.
- **Strategy pattern for inference** — `InferenceProvider` interface with `LiteRtLmProvider` for v1. Swap to MLC LLM later without touching business logic.
- **3-layer intelligence** — Rules are fast (microseconds), LLM is slow (~3-5s). Rules absorb repeat patterns so the LLM only handles novel notifications.
- **Batch processing** — LLM does not run on every notification in real-time. It processes in batches when the user opens the app or during device idle time via WorkManager.

## 3. Data Model (Room Database)

### 3.1 notifications

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT (PK, UUID) | Unique identifier |
| package_name | TEXT | e.g. `com.whatsapp` |
| app_name | TEXT | e.g. "WhatsApp" |
| title | TEXT | Sender / subject line |
| content | TEXT | Full message text |
| big_text | TEXT? | Expanded notification text (EXTRA_BIG_TEXT) |
| category | TEXT | `urgent` / `informational` / `noise` / `uncategorized` (default: `uncategorized`) |
| classified_by | TEXT | `rule` / `llm` / `user` / `pending` (default: `pending`) |
| rule_id | TEXT? | FK → rules table (if classified by rule) |
| conversation | TEXT? | Group/thread identifier |
| posted_at | LONG | Original notification timestamp |
| captured_at | LONG | When Focal captured it |
| processed_at | LONG? | When classified (NULL = pending for LLM) |
| is_summary | BOOL | Whether this is an LLM-generated summary row |
| summary_text | TEXT? | LLM-generated summary |
| extras_json | TEXT? | Raw notification extras as JSON |

**Indexes:** `(package_name, posted_at)`, `(category, posted_at)`, `(processed_at)` where NULL = pending queue.

### 3.2 rules

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT (PK) | Unique identifier |
| type | TEXT | `app_match` / `sender_match` / `keyword_match` / `llm_generated` |
| app | TEXT? | Package name to match (for app_match) |
| pattern | TEXT? | Sender name or keyword pattern |
| category | TEXT | Target category when rule matches |
| confidence | FLOAT | Rule confidence score |
| hit_count | INT | Times this rule has been applied |
| created_at | LONG | Creation timestamp |
| source | TEXT | `user_explicit` / `llm_extracted` / `system_default` |

### 3.3 corrections

| Column | Type | Description |
|--------|------|-------------|
| id | TEXT (PK) | Unique identifier |
| notification_id | TEXT | FK → notifications |
| old_category | TEXT | Category before correction |
| new_category | TEXT | Category after correction |
| created_at | LONG | When the user made the correction |

### 3.4 app_profiles

| Column | Type | Description |
|--------|------|-------------|
| package_name | TEXT (PK) | Android package name |
| app_name | TEXT | Display name |
| app_type | TEXT | `messaging` / `transactional` / `social` / `promotional` / `system` |
| default_category | TEXT? | User-set default category for this app |
| notification_count | INT | Total notifications received |
| noise_ratio | FLOAT | Percentage marked as noise |
| last_seen | LONG | Last notification timestamp |

**Data Lifecycle:**
- Raw notifications purged after 24 hours (privacy).
- Rules and app_profiles persist indefinitely (the system's learned knowledge).
- Corrections persist indefinitely (training signal for rule extraction).
- Storage estimate: ~500 notifications/day x ~0.5KB = ~250KB/day. Negligible.

## 4. Intelligence Engine

### 4.1 Classification Pipeline

```
Notification captured
        │
        ▼
┌──────────────────┐     MATCH     ┌─────────────────────┐
│  Layer 1: Rules  │──────────────→│ Done (classified_by  │
│  Check in order: │               │ = rule)              │
│  1. User explicit│               └─────────────────────┘
│  2. LLM-extracted│
│  3. System deflt │     NO MATCH
└──────────────────┘──────┐
                          ▼
              ┌─────────────────────┐
              │ Queue for Layer 2   │
              │ (processed_at=NULL) │
              └──────────┬──────────┘
                         │
          Triggered by: user opens Digest,
          periodic WorkManager, device idle+charging
                         │
                         ▼
              ┌──────────────────────┐
              │  Layer 2: LLM       │
              │  Gemma 3 1B (int4)  │
              │                     │
              │  Prompt includes:   │
              │  • Notification text│
              │  • App profile      │
              │  • Recent correct.  │
              │    (few-shot)       │
              │  • Time of day      │
              │                     │
              │  Output: JSON       │
              │  {category, reason, │
              │   confidence}       │
              └─────────────────────┘
```

**Layer 3 (Rule Extraction):** Runs every 20 corrections in a background WorkManager job. The LLM analyzes recent corrections and identifies patterns, generating new Layer 1 rules. Example: "User marked all 5 Rapido notifications as noise" → generates `{type: "app_match", app: "com.rapido", category: "noise"}`.

### 4.2 Learning Model

The LLM does not fine-tune on-device. "Learning" is implemented via three mechanisms:

1. **Direct rules:** User corrections with scope "all from this app" create immediate Layer 1 rules. Zero LLM cost.
2. **Few-shot prompting:** Recent corrections are injected into the classification prompt as examples. The LLM sees user preferences without weight changes.
3. **Periodic rule extraction:** Layer 3 uses the LLM to analyze correction patterns and generate new rules that absorb into Layer 1 for future instant matching.

Over time, Layer 1 handles an increasing percentage of notifications (~70% after one week of use), reducing LLM load.

### 4.3 Summarization Strategy

| App Type | Strategy | Digest Output |
|----------|----------|---------------|
| Messaging (WhatsApp, Telegram) | LLM summarizes per-conversation group | "Family Group: planning dinner Saturday, 23 messages" |
| Transactional (Swiggy, Zomato) | Deduplicate, keep latest status | "Swiggy: Order delivered at 8:32 PM" |
| Promotional (Rapido, Uber, Flipkart) | Count and suppress | "8 promotional notifications hidden" |
| Email (Gmail) | LLM classifies urgency per email | "1 urgent from boss, 3 newsletters" |
| System (Battery, updates) | Pass through as-is | "Battery low: 15%" |

### 4.4 Sample LLM Prompts

**Classification prompt:**
```
You are a notification classifier. Categorize as: urgent, informational, or noise.

App: WhatsApp | Sender: Mom | Time: 9:45 PM
Message: "Are you coming home for dinner? Dad is waiting."

User history (recent corrections):
- WhatsApp from Mom "call me" → user marked URGENT
- Swiggy "50% off biryani" → user marked NOISE
- WhatsApp from Work Group "standup in 5" → user marked URGENT

App profile: WhatsApp — messaging app, noise_ratio: 0.12

Respond in JSON only:
{"category": "...", "reason": "...", "confidence": 0.0-1.0}
```

**Summarization prompt (messaging):**
```
Summarize these WhatsApp notifications from "Family Group" in one sentence.
Focus on decisions, action items, and things the user needs to know.

[1] Mom: "Let's go to Manali this weekend"
[2] Dad: "I'll book the hotel"
[3] Sister: "I can't come, have exams"
[4] Mom: "Ok then next weekend?"
[5] Dad: "Works for me"

Summary:
```

### 4.5 InferenceProvider Interface

```kotlin
interface InferenceProvider {
    suspend fun initialize(modelPath: String)
    suspend fun generate(prompt: String, maxTokens: Int): String
    fun isReady(): Boolean
    fun close()
}
```

`LiteRtLmProvider` implements this for v1. `MlcLlmProvider` can be added later without changing any business logic in `Classifier`, `Summarizer`, or `RuleExtractor`.

### 4.6 Performance Budget (Snapdragon 720G)

| Operation | Latency | When |
|-----------|---------|------|
| Layer 1 (Rules) | <1ms | Every notification, real-time |
| Layer 2 (LLM classify) | ~3-5s | Batch, on app open or idle |
| Layer 2 (LLM summarize) | ~5-8s per app group | After classification batch |
| Layer 3 (Rule extraction) | ~10-15s | Every 20 corrections, background |

**Typical daily load:** ~500 notifications. After one week, rules catch ~70% → LLM processes ~150/day → ~10 min total inference (batched during idle/charging).

## 5. User Interface

### 5.1 Screen Map

Three screens with bottom navigation:

1. **Digest** (Home) — categorized notification view
2. **Apps** — per-app configuration
3. **Settings** — permissions, retention, about

### 5.2 Digest Screen

Three collapsible sections:

- **Urgent (red)** — always expanded. Individual notifications with sender, app, time, and message. Tappable to open original app.
- **Informational (blue)** — expanded by default. Shows LLM summaries for chatty apps, latest status for transactional apps. "Show more" for overflow.
- **Noise (grey)** — collapsed by default. Shows app names and counts: "Rapido (12) | Uber (8) | Flipkart (6)". Tap to expand.

**Header:** "Your Digest" with subtitle "Last 24h · 73 notifications · 3 need attention"

**Interactions:**
- Pull-to-refresh: triggers LLM processing on pending notifications
- Tap notification: expands full content, tap again to open original app
- Long-press notification: opens recategorization bottom sheet
- Swipe left: quick-recategorize (drag to Urgent/Info/Noise zones)
- Tap collapsed Noise section: expands to show individual items

### 5.3 Recategorization Flow

Bottom sheet triggered by long-press:

1. Shows the notification being recategorized with current category
2. Three category buttons: Urgent / Informational / Noise
3. Scope selection: "Just this notification" / "All [App] [type]" / "All [App] notifications"
4. Save Rule button

This directly creates Layer 1 rules when scope is broader than "just this one".

### 5.4 Apps Screen

List of all apps sorted by notification count. Each row: app icon, name, total count, noise ratio bar, default category badge. Tap to configure per-app defaults (always urgent, always noise, let AI decide).

### 5.5 Settings Screen

- Notification access permission status + link to grant
- Model status (downloaded / downloading / not downloaded)
- Data retention period (default 24h)
- About / version

### 5.6 Onboarding Flow (first launch)

1. Welcome screen explaining what Focal does
2. Grant notification access (redirects to system settings)
3. Download model weights (~400MB, progress bar)
4. Done — start capturing notifications

## 6. Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin |
| Min SDK | API 31 (Android 12) |
| UI | Jetpack Compose + Material 3 |
| Navigation | Compose Navigation |
| Database | Room |
| Dependency Injection | Hilt |
| Background Processing | WorkManager |
| LLM Runtime | LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) |
| LLM Model | Gemma 3 1B (1005 MB, `.litertlm` format) |
| Build System | Gradle (Kotlin DSL) |
| Architecture | MVVM + Repository pattern |

## 7. Project Structure

```
focal-glint/
├── app/
│   └── src/main/
│       ├── java/com/focal/
│       │   ├── di/                       # Hilt modules
│       │   ├── data/
│       │   │   ├── db/                   # Room entities, DAOs, database
│       │   │   ├── notification/         # NotificationListenerService
│       │   │   └── repository/           # Data access layer
│       │   ├── intelligence/
│       │   │   ├── InferenceProvider.kt  # Strategy interface
│       │   │   ├── LiteRtLmProvider.kt  # v1 implementation
│       │   │   ├── RulesEngine.kt        # Layer 1
│       │   │   ├── Classifier.kt         # Layer 2 classification
│       │   │   ├── Summarizer.kt         # Layer 2 summarization
│       │   │   └── RuleExtractor.kt      # Layer 3
│       │   ├── worker/
│       │   │   └── ClassificationWorker.kt
│       │   └── ui/
│       │       ├── digest/               # Digest screen
│       │       ├── apps/                 # App list screen
│       │       ├── settings/             # Settings screen
│       │       ├── correction/           # Recategorize bottom sheet
│       │       ├── onboarding/           # First-launch flow
│       │       └── theme/                # Material 3 theme
│       ├── res/
│       └── AndroidManifest.xml
├── model/                                # Gemma weights (gitignored)
├── build.gradle.kts
└── README.md
```

## 8. Implementation Plan (6 Weeks)

### Week 1: Project Setup + Notification Capture
- Android project scaffold (Kotlin, Compose, Hilt, Room)
- Room database with all 4 tables
- `NotificationListenerService` — capture and persist all notifications
- Basic "raw notification list" debug screen
- Test on Redmi: verify notifications are captured from all apps
- **Milestone:** See raw notifications from all apps inside Focal

### Week 2: Rules Engine + App Profiling
- Layer 1 Rules Engine (match by app, sender, keyword)
- App profile auto-detection (messaging vs transactional vs promotional)
- Seed ~20 default rules for common Indian apps (Swiggy, Zomato, Rapido, Paytm, PhonePe, Flipkart, etc.)
- `InferenceProvider` interface (strategy pattern stub)
- Basic Digest screen showing classified notifications (rules-only)
- **Milestone:** Rules-only classification working, visible in Digest

### Week 3: LLM Integration — Classification
- LiteRT-LM SDK integration + Gemma 3 1B model download flow
- `LiteRtLmProvider` implementing `InferenceProvider`
- Classification prompt engineering + JSON response parsing
- WorkManager job for batch classification of pending notifications
- **Critical test:** Measure inference latency on Redmi Note 10 Pro
- **Milestone:** LLM classifying notifications on actual device

### Week 4: LLM Integration — Summarization
- Per-app summarization prompts (messaging vs transactional strategies)
- Conversation grouping logic for WhatsApp/Telegram (using MessagingStyle extras)
- Deduplication logic for transactional apps
- Digest screen updated with real LLM summaries
- Performance tuning: batching strategy, idle/charging scheduling
- **Milestone:** Digest shows smart summaries, not raw notification text

### Week 5: v1.5 — Enhanced Classification & Topic-Based Digest (NEW)
- 4-class classification: Urgent / Actionable / Digest / Noise (replaces 3-class)
- Topic clustering engine with cross-app semantic deduplication
- New Digest screen: topic-based "news shorts" feed with drill-down detail views
- Topic Detail screen: template-based smart cards (billing, delivery, calendar) + LLM-generated details
- All Notifications screen: traditional 4-class categorized view
- Database migration v1→v2 (topics table, category migration)
- See: `docs/superpowers/specs/2026-04-18-focal-v1.5-topic-digest-design.md`
- **Milestone:** Topic-based Digest with semantic dedup working on device

### Week 6: User Correction + Learning Loop
- Recategorization bottom sheet UI (works on topics and individual notifications)
- Correction → rule scope selection flow
- Layer 3: LLM-based rule extraction from corrections (every 20 corrections)
- Few-shot prompt injection from correction history
- Apps screen with per-app configuration
- **Milestone:** Full learning loop — correct → rule → smarter future classification

### Week 7: Polish, Settings, Edge Cases
- Settings screen (retention period, permissions, model status, about)
- MIUI battery optimization handling (foreground service, user guidance)
- 24-hour auto-purge for raw notification data
- Onboarding flow (notification access grant, model download)
- Edge cases: reboot persistence, service restart, low memory graceful degradation
- UI polish: animations, empty states, loading indicators
- **Milestone:** Shippable MVP on Redmi Note 10 Pro

## 9. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| LLM too slow on Snapdragon 720G | Core feature unusable | Batch during idle/charging. Rules absorb repeat patterns. Worst case: ship rules-only + LLM optional. |
| MIUI kills background service | Notifications missed | Foreground service with persistent notification. Guide user to disable battery optimization for Focal. |
| Model download size (~400MB) | Poor first-launch experience | Download with progress bar on first launch. Model cached locally, never re-downloaded. |
| WhatsApp notification content limited | Poor summarization quality | Extract MessagingStyle extras for full conversation context. Fall back to title+text if unavailable. |
| Kotlin learning curve | Slower development | MVVM + Compose is well-documented. Claude pair-programs on Kotlin specifics. |

## 10. Future Roadmap (post-v1)

- **v2: Active notification management** — real-time re-ranking, auto-dismissing noise, custom notification shade replacement (premium feature)
- **v2: Cross-app intelligence** — linking Swiggy delivery notification with WhatsApp dinner conversation (requires local vector store)
- **v3: Context-aware DND** — auto-silencing based on time, location, calendar
- **v3: Multi-modal** — summarizing image-based notifications
- **v4: Ambient listening** — microphone input for context-aware processing
- **Future: Gemma 4 / larger models** — upgrade to Gemma4-E2B or E4B as device RAM allows
