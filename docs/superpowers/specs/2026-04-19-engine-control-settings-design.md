# Engine Control in Settings

**Goal:** Add model selection, download, and engine start/stop controls to the top of the Settings screen so users can manage the on-device LLM without ADB.

---

## 1. Models

Two selectable models stored in `ModelManager`:

| Label | File | URL | Size |
|---|---|---|---|
| Gemma 3 1B (low-end) | `gemma3-1b-it-int4.litertlm` | `https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm` | ~500 MB |
| Gemma 4 E2B (high-end) | `gemma-4-E2B-it.litertlm` | `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm` | 2.58 GB |

Both stored in internal app storage: `/data/data/com.focal/files/models/`. Deleted when app is uninstalled.

---

## 2. State

New fields added to `SettingsUiState`:

```kotlin
data class SettingsUiState(
    // existing
    val apps: List<AppOverride> = emptyList(),
    val snackbarMessage: String? = null,
    // new
    val selectedModel: ModelVariant = ModelVariant.GEMMA3_1B,
    val activeModel: ModelVariant? = null,       // which model file is present on disk
    val engineRunning: Boolean = false,
    val downloadProgress: Int? = null,            // null = idle, 0–100 = downloading, 100 = done
    val isRedownload: Boolean = false             // true when re-downloading existing model
)

enum class ModelVariant {
    GEMMA3_1B, GEMMA4_E2B
}
```

---

## 3. Architecture

Three files change, nothing new:

### `ModelManager.kt`
- Add `ModelVariant` enum (or import from UI layer via shared model)
- Add `modelFileFor(variant): File`
- Add `isModelAvailable(variant): Boolean`
- Add `suspend fun downloadModel(context, variant, onProgress: (Int) -> Unit)` — uses Android `DownloadManager`, polls every second
- Add `fun deleteModel(variant)` — deletes the file if it exists
- Add `fun activeVariant(): ModelVariant?` — checks which file exists on disk

### `SettingsViewModel.kt`
- Inject `ModelManager` and `InferenceProvider`
- On init: detect `activeVariant()` and `inferenceProvider.isReady()` to populate initial state
- `onModelSelected(variant)`:
  1. If variant != activeModel: stop engine → delete old file → set selectedModel → auto-start download
  2. If variant == activeModel: just update selectedModel (no download needed)
- `onDownload()`: call `ModelManager.downloadModel()`, emit progress to `downloadProgress`
- `onRedownload()`: same as `onDownload()`, sets `isRedownload = true`
- `onStartEngine()`: call `inferenceProvider.initialize(modelManager.modelFileFor(selectedModel).absolutePath)` → `engineRunning = true`
- `onStopEngine()`: call `inferenceProvider.close()` → `engineRunning = false`

### `SettingsScreen.kt`
- Add `EngineControlCard` composable at top of `LazyColumn`, above the app list

---

## 4. UI — EngineControlCard

```
┌─────────────────────────────────────────┐
│ AI Engine                               │
│                                         │
│ ○ Gemma 3 1B  · ~500 MB  (low-end)     │
│ ● Gemma 4 E2B · 2.58 GB  (high-end)    │
│                                         │
│ [Download 45%]              [Stop]      │
│ Re-download                             │
└─────────────────────────────────────────┘
```

### Download button states

| Condition | Button text | Enabled |
|---|---|---|
| Model not on disk | "Download" | yes |
| Downloading | "Downloading 45%" | no |
| Model on disk | "Downloaded ✓" | no |
| Re-downloading | "Downloading 45%" | no |

"Re-download" appears as a small text button below only when model is already on disk and not currently downloading.

### Start/Stop button states

| Condition | Button text | Enabled |
|---|---|---|
| No model on disk | "Start" | no |
| Model on disk, engine stopped | "Start" | yes |
| Model on disk, engine running | "Stop" | yes |
| Downloading | "Start" | no |

### Model switch behaviour

When user taps the other radio option:
1. `inferenceProvider.close()` — engine stops immediately
2. `deleteModel(currentActiveModel)` — old file deleted
3. `downloadModel(newVariant)` — download starts automatically, progress shown

---

## 5. Files to Change

| File | Change |
|---|---|
| `ModelManager.kt` | Add `ModelVariant`, `modelFileFor()`, `isModelAvailable()`, `downloadModel()`, `deleteModel()`, `activeVariant()` |
| `SettingsViewModel.kt` | Inject `ModelManager` + `InferenceProvider`, add engine/download state and actions |
| `SettingsScreen.kt` | Add `EngineControlCard` at top of LazyColumn |
