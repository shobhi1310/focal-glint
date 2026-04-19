# Setup Screen — AI Engine Configuration

**Goal:** Add a dedicated Setup page with step-based UI for notification access, model selection, model download, and engine start/stop. Accessible via a "Setup AI Engine" row at the top of the existing Settings screen.

---

## 1. Navigation

- New route `Screen.Setup` added to `FocalNavigation`
- Settings screen gets a tappable "Setup AI Engine" row at the top that navigates to Setup
- Setup screen has a back arrow (standard nav back)

---

## 2. Models

Two selectable models defined in `ModelManager`:

| Label | File | URL | Size |
|---|---|---|---|
| Gemma 3 1B (low-end) | `gemma3-1b-it-int4.litertlm` | `https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm` | ~500 MB |
| Gemma 4 E2B (high-end) | `gemma-4-E2B-it.litertlm` | `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm` | 2.58 GB |

Both stored in internal app storage: `/data/data/com.focal/files/models/`. Deleted on uninstall.

---

## 3. Setup Steps

Four sequential steps. Each shows a status badge: `✓` (complete) or `PENDING`.

### Step 1: Notification Access
- Status: complete if `NotificationManagerCompat.getEnabledListenerPackages(context)` contains `com.focal`
- Action button: "Open Settings" → `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`
- No user input required beyond granting permission

### Step 2: Select Model
- Status: complete once user has tapped a radio option (selection persisted in `SharedPreferences`)
- Radio options: Gemma 3 1B / Gemma 4 E2B
- Switching selection when a model is already downloaded: stop engine → delete old file → reset Steps 3 and 4

### Step 3: Download Model
- Status: complete when selected model file is present on disk (`modelFile.exists() && size > 100 MB`)
- Button states:
  - Model not on disk, not downloading → "Download" (enabled)
  - Downloading → "Downloading 45%" (disabled)
  - Model on disk → "Downloaded ✓" (disabled) + small "Re-download" text button below
- Re-download: stops engine, deletes file, restarts download

### Step 4: Start Engine
- Status: complete when `inferenceProvider.isReady() == true`
- Button states:
  - No model on disk → "Start" (disabled)
  - Model on disk, engine stopped → "Start" (enabled)
  - Engine running → "Stop" (enabled)
- Stop: calls `inferenceProvider.close()`, frees RAM, classification falls back to rules-only

---

## 4. State

```kotlin
enum class ModelVariant(
    val fileName: String,
    val url: String,
    val displayName: String,
    val sizeLabel: String
) {
    GEMMA3_1B(
        fileName = "gemma3-1b-it-int4.litertlm",
        url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
        displayName = "Gemma 3 1B",
        sizeLabel = "~500 MB"
    ),
    GEMMA4_E2B(
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B",
        sizeLabel = "2.58 GB"
    )
}

data class SetupUiState(
    val notificationAccessGranted: Boolean = false,
    val selectedModel: ModelVariant = ModelVariant.GEMMA3_1B,
    val activeModel: ModelVariant? = null,       // model file present on disk
    val downloadProgress: Int? = null,            // null = idle, 0–100 = in progress
    val engineRunning: Boolean = false
)
```

---

## 5. Architecture

### New files
- `SetupScreen.kt` — composable with 4 step cards
- `SetupViewModel.kt` — state + actions, injects `ModelManager` + `InferenceProvider`

### Modified files
- `ModelManager.kt` — add `ModelVariant` enum, `modelFileFor()`, `isModelAvailable()`, `activeVariant()`, `deleteModel()`, `downloadModel()`
- `SettingsScreen.kt` — add "Setup AI Engine" nav row at top
- `Screen.kt` — add `Setup` route
- `FocalNavigation.kt` — wire Setup route

---

## 6. SetupViewModel Actions

```
onResume()            → re-check notification access (user may have just granted it)
onOpenNotifSettings() → launch ACTION_NOTIFICATION_LISTENER_SETTINGS
onModelSelected(v)    → if v != activeModel: stop engine + delete old + reset download state
                        if v == activeModel: just update selectedModel
onDownload()          → ModelManager.downloadModel() with progress callback
onRedownload()        → stop engine + delete + re-download
onStartEngine()       → inferenceProvider.initialize(modelPath) → engineRunning = true
onStopEngine()        → inferenceProvider.close() → engineRunning = false
```

---

## 7. Files to Change

| File | Change |
|---|---|
| `ModelManager.kt` | Add `ModelVariant` enum + file helpers + `downloadModel()` |
| `Screen.kt` | Add `object Setup : Screen("setup")` |
| `FocalNavigation.kt` | Add composable route for SetupScreen |
| `SettingsScreen.kt` | Add "Setup AI Engine" nav row at top of LazyColumn |
| `SettingsViewModel.kt` | Pass `onNavigateToSetup` callback through or handle in screen |
| `SetupViewModel.kt` | New — all engine control state and actions |
| `SetupScreen.kt` | New — 4-step UI |
