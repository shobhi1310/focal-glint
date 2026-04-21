# Gemma 300M Embedding Provider Design

**Date:** 2026-04-21
**Branch:** dev-darahas

## Goal

Replace Gecko (256-token limit) with Gemma 300M (512-token limit) as the on-device text embedding model, using MediaPipe `TextEmbedder`. Both models output 768-dim vectors so no DB migration is required. Add a developer settings toggle to switch between models at runtime.

## Background

- Current provider: `GeckoEmbeddingProvider` using `localagents-rag:0.1.0` → `GeckoEmbeddingModel`
- Hard limit of 256 tokens causing overflow errors on code-heavy / mixed-language notifications
- `embeddinggemma-300M_seq512_mixed-precision.tflite` (179MB) already on-device at `models/embeddings/`
- Both models produce 768-dim float vectors — stored embeddings are incompatible across models (different vector spaces) so switching requires a full re-embed

## Architecture

### New dependency

```kotlin
implementation("com.google.mediapipe:tasks-text:latest.release")
```

### New class: `GemmaEmbeddingProvider`

Implements existing `EmbeddingProvider` interface. Uses `TextEmbedder` from `tasks-text`.

- `initialize(modelPath, tokenizerPath, useGpu)` — `tokenizerPath` ignored (tokenizer bundled in TFLite model metadata). Builds `TextEmbedder` with `Delegate.GPU` or `Delegate.CPU` based on `useGpu`.
- `embed(text)` — calls `textEmbedder.embed(text).embeddingResult().embeddings().first().floatEmbedding()!!.toFloatArray()`, then L2-normalizes via `VectorMath.l2Normalize()`.
- `isReady()` — returns `textEmbedder != null`
- `close()` — calls `textEmbedder?.close()`, sets to null

### New class: `SwitchableEmbeddingProvider`

Thin delegate wrapper bound by Hilt as the singleton `EmbeddingProvider`. Holds `@Volatile var inner: EmbeddingProvider`. All interface calls forward to `inner`. Allows runtime model swapping without changing any downstream injection sites.

```kotlin
class SwitchableEmbeddingProvider : EmbeddingProvider {
    @Volatile var inner: EmbeddingProvider = GeckoEmbeddingProvider()
    override suspend fun embed(text: String) = inner.embed(text)
    // ... forwards all calls
}
```

### `ModelManager` additions

- `gemmaEmbeddingModelFile: File` — `File(embeddingModelDir, GEMMA_MODEL_FILENAME)`
- `isGemmaEmbeddingAvailable: Boolean` — file exists and size > 50MB
- `getEmbeddingModelPreference(): EmbeddingModelType` — reads SharedPreferences, defaults to `GECKO`
- `saveEmbeddingModelPreference(type: EmbeddingModelType)` — writes SharedPreferences

```kotlin
enum class EmbeddingModelType { GECKO, GEMMA }

companion object {
    const val GEMMA_MODEL_FILENAME = "embeddinggemma-300M_seq512_mixed-precision.tflite"
}
```

### `FocalApplication` startup

On app start, read `getEmbeddingModelPreference()`. If `GEMMA` but `isGemmaEmbeddingAvailable` is false, fall back to `GECKO` silently. Initialize `SwitchableEmbeddingProvider.inner` accordingly before calling `initialize()`.

### `NotificationRepository` addition

```kotlin
suspend fun clearAllEmbeddings()
```

Sets `embedding = null` and `embedded_at = null` for all notifications. Called when switching embedding models.

## Settings UI

Location: Developer Settings section (existing).

Add **"Embedding Model"** preference row showing current selection. Two options presented as a dialog or segmented control:

| Option | Condition |
|---|---|
| Gecko (110M, 256 tokens) | Always selectable if Gecko file exists |
| Gemma 300M (512 tokens) | Grayed out + "Model not downloaded" label if file missing |

### Switch flow (in `SettingsViewModel`)

1. Check target model file exists and size > 50MB — if not, show snackbar error, abort
2. If switching to same model already active, no-op
3. `embeddingProvider.close()` (closes current inner)
4. Swap `SwitchableEmbeddingProvider.inner` to new provider instance
5. `embeddingProvider.initialize(newModelPath, "", useGpu)` — GPU pref from `modelManager.getBackendPreference()`
6. `notificationRepository.clearAllEmbeddings()` — wipe incompatible vectors
7. `modelManager.saveEmbeddingModelPreference(newType)`
8. Set `TopicEngine.pendingFullRebuild.set(true)`
9. Enqueue `ClassificationWorker` to re-embed and rebuild topics

### GPU pref respected

Both `GeckoEmbeddingProvider` and `GemmaEmbeddingProvider` accept `useGpu: Boolean` in `initialize()`. The existing `ModelBackendPolicy.useGpuForEmbeddings()` continues to gate this. When the GPU/CPU toggle changes in settings, the existing re-init flow already closes and re-initializes `embeddingProvider` — no changes needed there since `SwitchableEmbeddingProvider` forwards transparently.

## Error handling

- **File missing on switch**: snackbar, no state change
- **GPU init failure on Gemma**: catch exception, retry with CPU, log warning (mirrors existing LLM GPU fallback pattern)
- **Gemma preferred at startup but missing**: silently fall back to Gecko, do not persist the fallback

## Files changed

| File | Change |
|---|---|
| `app/build.gradle.kts` | Add `tasks-text:latest.release` |
| `GemmaEmbeddingProvider.kt` | New — MediaPipe TextEmbedder impl |
| `SwitchableEmbeddingProvider.kt` | New — delegate wrapper |
| `EmbeddingModelType.kt` | New — enum |
| `ModelManager.kt` | Add Gemma file/pref accessors |
| `di/IntelligenceModule.kt` | Bind `SwitchableEmbeddingProvider` |
| `FocalApplication.kt` | Read pref, set inner before init |
| `NotificationRepository.kt` | Add `clearAllEmbeddings()` |
| `NotificationDao.kt` | Add `clearAllEmbeddings()` query |
| `SettingsViewModel.kt` | Switching logic |
| `SettingsScreen.kt` | Embedding model selector UI |
