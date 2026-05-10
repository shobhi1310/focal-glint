# Pulse Widgets Design

Live, compact dashboards powered by LLM extraction and code-based computation.

## Context

Focal currently classifies notifications (matters/noise), clusters them into topics via embeddings, and generates narrative summaries via LLM. The Pulse feature adds user-configurable widgets that extract structured data from notifications and compute live metrics (sum, count, list, etc.) without additional LLM cost.

### LiteRT-LM Constraint

LiteRT-LM supports one active conversation per Engine instance. The KV cache is a single shared memory buffer (~150MB for Gemma 3 1B at 8K context). Concurrent conversations corrupt the cache. All LLM calls must be serialized via the existing single-thread `llmDispatcher`. This design adds zero extra LLM conversations by merging extraction into the existing classification conversation.

## Architecture

### Extract-Once, Compute-Many

One LLM conversation handles both classification and structured data extraction using tool calling. Widget recipes then run as pure code against the extracted data.

Pipeline per cycle (2 LLM conversations, same as today):

1. **Conv #1: Classify + Extract** (tool calling) — For each notification, call `classify()` then the appropriate `extract_*()` tool(s). A notification can match multiple extraction tools (e.g., Swiggy payment = finance + logistics).
2. **Embedding + Topic assign** (no LLM) — unchanged.
3. **Widget compute** (no LLM) — Run each active widget's recipe against ExtractedData. Update WidgetState.
4. **Conv #2: Topic narratives** (free text) — unchanged, separate TopicNarrativeWorker.

Widgets update after step 3 (~8s from notification arrival) without waiting for narrative generation.

### Extraction Tools

Only registered for widget categories the user has active. No active widgets = no extraction tools = identical behavior to today.

```
classify(index, category, reason)              // existing
extract_finance(index, amount, merchant, category, direction)   // new
extract_work(index, entity, sender, action, repo?)              // new
extract_personal(index, sender, channel, count, snippet?)       // new
extract_logistics(index, item, merchant, status, eta_minutes?)  // new
```

System prompt addition: "For each notification: 1) Call classify() with matters or noise. 2) If matters, also call the matching extract_*() tool(s). 3) A notification can match multiple extract tools."

Tool-count ceiling: ~50-100 tokens per tool schema. 10 widget types ~ 500-1000 tokens of 8K context. Practical limit ~15-20 active widget types on Gemma 3 1B.

## Data Model

### Room Migration v6 to v7

Add `extracted` Boolean column to `notifications` table. Create 3 new tables.

### NotificationEntity (existing, modified)

Add column:
- `extractedCategories: String? = null` — JSON list of categories already extracted for this notification (e.g., `["finance","logistics"]`). Null means not yet extracted. When a user adds a new widget category, notifications missing that category in their list are re-processed for extraction without re-running categories already done.

### WidgetConfigEntity (new)

Stores user's widget definitions. Persists until user deletes the widget. NOT wiped by 2AM DailyResetWorker.

| Column | Type | Description |
|--------|------|-------------|
| id | String (PK) | UUID |
| category | String | finance, work, personal, logistics |
| title | String | "Money today" |
| operation | String | SUM, COUNT, LATEST, LIST, MAX, STATUS |
| extractionTool | String | "extract_finance" |
| field | String? | "amount" for SUM, null for COUNT |
| groupBy | String? | "merchant", "sender", null |
| filterApps | String? | JSON list of package names, null = auto |
| headlineTemplate | String | Template string, e.g., "₹{result}" |
| source | String | TEMPLATE or LLM_GENERATED |
| position | Int | Display order in grid |
| createdAt | Long | |
| updatedAt | Long | |

### ExtractedDataEntity (new)

Structured data extracted by LLM tools. One notification can produce multiple rows. Persists until user wipes the widget or deletes it. NOT wiped by 2AM DailyResetWorker.

| Column | Type | Description |
|--------|------|-------------|
| id | Long (PK, auto) | |
| notificationId | Long | FK to NotificationEntity |
| category | String | finance, work, personal, logistics |
| data | String | JSON blob, schema per category |
| extractedAt | Long | |

JSON schemas per category:

- **finance**: `{ amount: Double, merchant: String, category: String, direction: String }`
- **work**: `{ entity: String, sender: String, action: String, repo: String? }`
- **personal**: `{ sender: String, channel: String, count: Int, snippet: String? }`
- **logistics**: `{ item: String, merchant: String, status: String, eta_minutes: Int? }`

### WidgetStateEntity (new)

Pre-computed display state. UI observes via Flow. Recomputed after each extraction cycle. Persists until user wipes or deletes. NOT wiped by 2AM DailyResetWorker.

| Column | Type | Description |
|--------|------|-------------|
| widgetId | String (PK) | FK to WidgetConfigEntity |
| headline | String | "₹4,705" |
| subtitle | String? | "+ ₹425 at Swiggy just now" |
| badge | String? | "+₹425 Swiggy" |
| detailJson | String? | List items, grouped data for expanded view |
| sourceAppIcons | String? | JSON list of packageNames contributing |
| itemCount | Int | Notification count contributing to this widget |
| lastUpdatedAt | Long | |

### Widget Data Lifecycle

Widget data is user-controlled and does NOT participate in the 2AM daily reset:

- **WidgetConfig**: persists forever until user deletes widget.
- **ExtractedData**: persists until user explicitly wipes, or deletes widget.
- **WidgetState**: recomputed on refresh, persists otherwise.

User actions from overflow menu:
- **Refresh**: re-run extraction on new/unextracted notifications, recompute widget state. Keeps historical data.
- **Wipe data**: clear all ExtractedData + WidgetState for that widget. Config stays. Next refresh rebuilds from scratch.
- **Delete widget**: remove config + extracted data + state entirely.

## Widget Compute Engine

Pure code, no LLM. Runs after extraction, computes each active widget's state from ExtractedData.

```kotlin
fun computeWidget(config: WidgetConfig, data: List<ExtractedData>): WidgetState {
    val relevant = data
        .filter { it.category == config.category }
        .filter { config.filterApps == null || it.appPackage in config.filterApps }

    return when (config.operation) {
        SUM    -> sumOf(relevant, config.field)
        COUNT  -> countOf(relevant)
        LATEST -> latestOf(relevant)
        LIST   -> groupBy(relevant, config.groupBy)
        MAX    -> maxOf(relevant, config.field)
        STATUS -> rollupStatus(relevant)
    }
}
```

Operations:
- **SUM**: Add numeric field values. E.g., sum of `amount` = ₹4,705.
- **COUNT**: Count matching extracted items. E.g., 3 PRs.
- **LATEST**: Return the most recent extracted item. E.g., "Mom just called again."
- **LIST**: Group by a field (sender, merchant). E.g., PR #482 — Anika, PR #501 — Ravi.
- **MAX**: Return the item with the highest numeric field. E.g., biggest transaction.
- **STATUS**: Roll up a status field into counts. E.g., 1 in transit, 1 delivered.

## Starter Templates (v1)

Four pre-built templates. User selects one in the wizard, can adjust operation and app sources.

### Finance — "Money today"
- Extraction tool: `extract_finance`
- Default operation: SUM on `amount`, grouped by `merchant`
- Default apps: HDFC Bank, ICICI, Paytm, PhonePe, GPay
- Headline: "₹{sum}" | Badge: latest transaction | Detail: per-merchant breakdown

### Work — "Work pulse"
- Extraction tool: `extract_work`
- Default operation: COUNT + LIST grouped by `sender`
- Default apps: GitHub, Slack, Linear, Gmail
- Headline: "{count} PRs need you" | Badge: latest arrival | Detail: entity — sender list

### Personal — "People who reached out"
- Extraction tool: `extract_personal`
- Default operation: LIST grouped by `sender`
- Default apps: Phone, WhatsApp, Messages, Telegram
- Headline: "{top_sender} · {count}" | Badge: latest contact method | Detail: sender list with channel

### Logistics — "Deliveries"
- Extraction tool: `extract_logistics`
- Default operation: STATUS rollup
- Default apps: Amazon, Swiggy, Zomato, Flipkart, Dunzo
- Headline: "{in_transit_count} in transit" | Badge: latest status change | Detail: per-item status list

## UI Design

### Navigation

Replace "All" tab with "Pulse": Today · **Pulse** · Tune.

### Pulse Screen (grid)

- Header: "Pulse" title, "LIVE" indicator, subtitle "Ask your day a question.", stats line ("4 widgets · 3 updated in the last minute").
- Body: 2-column LazyVerticalGrid of compact PulseCards.
- FAB: "+ New widget" button, opens PulseWizard bottom sheet.
- Pull-to-refresh: triggers extraction + recompute for all widgets.

### PulseCard (compact, in grid)

Shows:
- Category label (uppercase, letter-spaced)
- Title (from WidgetConfig)
- Headline (from WidgetState — the big number/text)
- Badge (latest change, pill-shaped)
- App icons row + relative timestamp

Tap navigates to PulseDetailScreen.

### PulseDetailScreen (expanded, full-screen)

Shows everything from compact card, plus:
- Subtitle (contextual line below headline)
- Breakdown / list section (from detailJson, e.g., per-merchant amounts or per-sender items)
- Source notifications section (ExtractedData joined with NotificationEntity — original text + relative time)
- Overflow menu: Refresh, Wipe data, Edit, Delete widget
- Back navigation to grid.

### PulseWizard (3-step bottom sheet, template-based v1)

**Step 1 — Template**: "What do you want to track?" Pick from 4 templates (Finance, Work, Personal, Logistics). Greyed-out "Ask a custom question..." placeholder for future LLM-generated widgets.

**Step 2 — Recipe**: "How should Focal compute it?" Shows auto-selected operation from template. User can override by tapping another operation (Sum/Count/Latest/List/Max/Status). Live preview showing current value from existing extracted data.

**Step 3 — Sources**: "From which apps?" Default: "Auto — let Focal decide" (filterApps = null). Or pick specific apps from list populated by distinct packageNames in NotificationEntity. "Create widget" button saves WidgetConfig and triggers initial compute.

### Future Extension: LLM-Generated Widgets

The WidgetConfig data class has `source: TEMPLATE | LLM_GENERATED`. When the free-text path is built later, the LLM generates a WidgetConfig with the same shape. The compute engine and UI don't care about the source. Step 1 of the wizard gains a text input field; steps 2-3 become pre-filled confirmation screens.

## File Structure

### New Files

```
intelligence/
  ExtractionToolSet.kt         // extraction tool classes (finance, work, personal, logistics)
  WidgetComputeEngine.kt       // pure-code recipe computation

data/db/entity/
  WidgetConfigEntity.kt
  ExtractedDataEntity.kt
  WidgetStateEntity.kt

data/db/dao/
  WidgetConfigDao.kt
  ExtractedDataDao.kt
  WidgetStateDao.kt

data/repository/
  WidgetRepository.kt          // CRUD for configs + compute trigger

ui/pulse/
  PulseScreen.kt               // grid + header + FAB
  PulseViewModel.kt            // observes WidgetState Flow, handles CRUD
  PulseCard.kt                 // compact card composable
  PulseDetailScreen.kt         // expanded full-screen detail
  PulseWizard.kt               // 3-step bottom sheet
```

### Modified Files

```
data/db/FocalDatabase.kt       // v7 migration, add 3 tables + extracted column
intelligence/Classifier.kt     // register extraction tools alongside classify
intelligence/LiteRtLmProvider.kt  // pass extraction tools to conversation
worker/ClassificationWorker.kt // add widget compute step after topic assign
worker/DailyResetWorker.kt     // explicitly skip widget tables
ui/navigation/Screen.kt        // All -> Pulse tab + pulse detail route
ui/navigation/NavGraph.kt      // add pulse + pulse/{widgetId} routes
```

## Reactive Update Flow

Widgets use Room Flow for reactive updates — no polling, no periodic refresh.

1. Notification arrives → stored in DB.
2. ClassificationWorker runs → classify + extract in one LLM conversation → ExtractedData rows written.
3. Widget compute runs → WidgetState updated in Room.
4. UI observes `WidgetStateDao.observeAll(): Flow<List<WidgetState>>` → auto-recomposes.

"LIVE" indicator: true if any widget's `lastUpdatedAt` is within the last 5 minutes.
Badge: computed as diff between current and previous widget state (tracked in WidgetComputeEngine).
