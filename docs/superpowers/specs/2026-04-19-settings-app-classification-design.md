# Settings Page — App Classification Overrides

**Goal:** Let users override per-app classification (matters/noise/auto) from a Settings page, so the system learns their preferences without needing to retrain the LLM.

**Core insight:** The rules engine already supports `user_explicit` rules that override `system_default`. The Settings page is a UI for managing those rules — no engine changes needed.

---

## 1. Architecture

The Settings page shows a scrollable list of all apps that have sent notifications, sorted by notification count descending. Each app row has a 3-state segmented control:

- **Matters** — creates a `user_explicit` app_match rule with `category = "matters"`
- **Auto** (default) — no user rule; system defaults + LLM classify
- **Noise** — creates a `user_explicit` app_match rule with `category = "noise"`

Changes are debounced: after 3 seconds of no toggles, rules are saved to the database and a `ClassificationWorker` is enqueued with `ExistingWorkPolicy.REPLACE` to reclassify notifications and regenerate topics.

No new database tables or migrations are needed. Everything uses the existing `rules` and `app_profiles` tables.

## 2. Data Flow

### Reading State

1. Query `AppProfileEntity` for all apps — provides `packageName`, `appName`, `notificationCount`
2. Query `RuleEntity` where `source = "user_explicit"` and `type = "app_match"` — provides existing user overrides
3. Query `RuleEntity` where `source = "system_default"` and `type = "app_match"` — provides system defaults for hint text
4. For each app, derive toggle state:
   - Has `user_explicit` rule with `category = "matters"` → **Matters**
   - Has `user_explicit` rule with `category = "noise"` → **Noise**
   - No user rule → **Auto**
5. When on Auto, show the effective system default as a hint (e.g., "system: noise") or "system: llm" if no system rule exists

### Writing Changes

- **Toggle to Matters/Noise:** Upsert a `RuleEntity` with `source = "user_explicit"`, `type = "app_match"`, `app = packageName`, `category = matters/noise`, `confidence = 1.0f`
- **Toggle to Auto:** Delete the `user_explicit` rule for that app (falls back to system default or LLM)
- **Debounce:** Start a 3-second timer on each toggle. Reset on subsequent toggles. When timer fires:
  1. Persist all pending rule changes to the database
  2. Enqueue `ClassificationWorker` with `ExistingWorkPolicy.REPLACE`
  3. Show Snackbar: "Changes saved · refreshing digest..."

### DAO Requirements

- `RuleDao.getUserRulesForApp(packageName): RuleEntity?` — query user_explicit app_match rule
- `RuleDao.getAllUserRules(): List<RuleEntity>` — all user_explicit rules
- `RuleDao.getAllSystemDefaults(): List<RuleEntity>` — all system_default rules
- `RuleDao.deleteUserRuleForApp(packageName)` — delete user override for an app
- `RuleDao.upsertRule(rule: RuleEntity)` — insert or replace a rule
- `AppProfileDao.getAllProfilesSorted(): List<AppProfileEntity>` — sorted by notificationCount desc

## 3. UI Layout

### Screen Structure

```
┌──────────────────────────────────┐
│ Settings                         │  ← headlineLarge
│ Choose how each app is classified│  ← bodyMedium, muted
│                                  │
│ ┌──────────────────────────────┐ │
│ │ WhatsApp                     │ │
│ │ 22 notifications · system:   │ │
│ │ matters                      │ │
│ │ [Matters] [Auto] [Noise]     │ │
│ └──────────────────────────────┘ │
│ ┌──────────────────────────────┐ │
│ │ Google News                  │ │
│ │ 8 notifications · system:    │ │
│ │ noise                        │ │
│ │ [Matters] [Auto] [Noise]     │ │
│ └──────────────────────────────┘ │
│ ...                              │
└──────────────────────────────────┘
```

### App Row

- **App name:** `titleMedium`, `onBackground` color
- **Subtitle:** `bodySmall`, muted — "{count} notifications · system: {default}" or "{count} notifications · system: llm" if no system rule
- **Segmented button:** `SingleChoiceSegmentedButtonRow` with 3 segments: Matters | Auto | Noise. Selected segment highlighted with theme primary color.

### States

- **Empty:** "No apps seen yet. Notifications will appear here as they arrive."
- **Normal:** Scrollable list of app rows
- **After save:** Snackbar at bottom: "Changes saved · refreshing digest..."

## 4. Components

### New Files

- `app/src/main/java/com/focal/ui/settings/SettingsScreen.kt` — Composable screen
- `app/src/main/java/com/focal/ui/settings/SettingsViewModel.kt` — ViewModel with debounce logic

### Modified Files

- `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt` — replace placeholder with SettingsScreen
- `app/src/main/java/com/focal/data/db/dao/RuleDao.kt` — add user rule queries
- `app/src/main/java/com/focal/data/db/dao/AppProfileDao.kt` — add sorted query
- `app/src/main/java/com/focal/data/repository/RuleRepository.kt` — add user rule management methods

## 5. ViewModel Design

```kotlin
data class AppOverride(
    val packageName: String,
    val appName: String,
    val notificationCount: Int,
    val systemDefault: String?,    // "matters", "noise", or null (llm)
    val userOverride: String?      // "matters", "noise", or null (auto)
)

data class SettingsUiState(
    val apps: List<AppOverride> = emptyList(),
    val snackbarMessage: String? = null
)
```

The ViewModel:
1. Combines `AppProfileEntity` list with user rules and system default rules into `List<AppOverride>`
2. Exposes `uiState: StateFlow<SettingsUiState>`
3. `onToggle(packageName, newState)` — updates local state immediately (optimistic), starts/resets 3-second debounce timer
4. When debounce fires: persists to DB, enqueues worker, sets snackbar message
5. Snackbar auto-dismisses after 2 seconds

## 6. Files to Change

| File | Change |
|------|--------|
| `RuleDao.kt` | Add `getUserRuleForApp`, `getAllUserRules`, `getAllSystemDefaults`, `deleteUserRuleForApp`, `upsertRule` |
| `AppProfileDao.kt` | Add `getAllProfilesSorted` |
| `RuleRepository.kt` | Add `getUserRules`, `getSystemDefaults`, `setUserOverride`, `clearUserOverride` |
| `SettingsViewModel.kt` | New — debounced toggle logic, combines app profiles + rules |
| `SettingsScreen.kt` | New — LazyColumn of app rows with segmented buttons |
| `FocalNavigation.kt` | Replace placeholder `Text("Settings")` with `SettingsScreen()` |
