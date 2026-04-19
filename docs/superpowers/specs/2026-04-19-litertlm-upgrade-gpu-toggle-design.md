# LiteRT-LM Upgrade + GPU/CPU Toggle Design

**Date:** 2026-04-19  
**Goal:** Fix the native crash by upgrading litertlm to 0.10.2, enable GPU inference by default, and add a CPU/GPU toggle in Settings that restarts the engine immediately.

---

## Problem

focal-glint uses `litertlm-android:0.8.0` which crashes with a SIGABRT because the JNI library is not auto-loaded in that version. The API also diverged (e.g. `Content` → `Contents`). Companion uses `0.10.2` which fixes both issues.

---

## Design

### Section 1: Library Upgrade + LiteRtLmProvider Fix

**`app/build.gradle.kts`**
- Change `litertlm-android:0.8.0` → `litertlm-android:0.10.2`

**`LiteRtLmProvider.kt`**
- Update API: `Content` → `Contents`, message construction to match 0.10.2
- Add `cacheDir = context.cacheDir.absolutePath` and `maxNumTokens = 8192` to `EngineConfig`
- Accept `Context` (needed for cacheDir) — inject via constructor
- Accept `useGpu: Boolean` parameter in `initialize(modelPath, useGpu)` — uses `Backend.GPU()` if true, `Backend.CPU` if false
- Remove `System.loadLibrary` workaround (0.10.2 handles internally)

**`InferenceProvider` interface**
- Change `initialize` signature to `suspend fun initialize(modelPath: String, useGpu: Boolean = true)`
- Add `suspend fun restart(modelPath: String, useGpu: Boolean)` — closes current engine, re-initializes with new backend

---

### Section 2: Backend Preference Storage

**`ModelManager.kt`**
- Add `fun getBackendPreference(context: Context): Boolean` — reads `"backend_preference"` from `"focal_prefs"` SharedPreferences, defaults to `true` (GPU)
- Add `fun saveBackendPreference(context: Context, useGpu: Boolean)` — writes `"gpu"` or `"cpu"` to the same prefs

**`FocalApplication.initializeLlmIfModelExists()`**
- Read backend preference via `ModelManager.getBackendPreference(this)`
- Pass to `inferenceProvider.initialize(modelPath, useGpu)`

---

### Section 3: Settings UI — CPU/GPU Toggle

**`SettingsUiState`**
- Add `val useGpu: Boolean = true`
- Add `val engineRestarting: Boolean = false`

**`SettingsViewModel`**
- On init: read `ModelManager.getBackendPreference()` and populate `useGpu` in state
- Add `fun setBackendPreference(useGpu: Boolean)`:
  1. Set `engineRestarting = true`
  2. Save via `ModelManager.saveBackendPreference()`
  3. Call `inferenceProvider.restart(modelPath, useGpu)`
  4. Set `engineRestarting = false`
- `modelPath` sourced from `ModelManager.modelPath`

**`SettingsScreen.kt`**
- Add segmented toggle at top of screen labeled "Inference Backend" with `GPU` / `CPU` options
- Disabled + shows loading indicator while `engineRestarting = true`
- Calls `viewModel.setBackendPreference(useGpu)` on selection change

---

## Files Changed

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Upgrade litertlm 0.8.0 → 0.10.2 |
| `intelligence/LiteRtLmProvider.kt` | Fix API, add context/useGpu params, add restart() |
| `intelligence/InferenceProvider.kt` | Update initialize signature, add restart() |
| `intelligence/ModelManager.kt` | Add getBackendPreference / saveBackendPreference |
| `FocalApplication.kt` | Pass useGpu to initialize |
| `ui/settings/SettingsUiState` (in SettingsViewModel.kt) | Add useGpu, engineRestarting fields |
| `ui/settings/SettingsViewModel.kt` | Add setBackendPreference() |
| `ui/settings/SettingsScreen.kt` | Add GPU/CPU segmented toggle |
| `di/IntelligenceModule.kt` | Update LiteRtLmProvider binding if constructor changes |
