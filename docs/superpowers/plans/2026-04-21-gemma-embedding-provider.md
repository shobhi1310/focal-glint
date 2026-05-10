# Gemma 300M Embedding Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Gecko (256-token limit) with selectable Gemma 300M (512-token limit) embedding via MediaPipe TextEmbedder, with a developer settings toggle and GPU/CPU pref respected.

**Architecture:** A new `SwitchableEmbeddingProvider` wraps either `GeckoEmbeddingProvider` or `GemmaEmbeddingProvider` and is the single Hilt-bound singleton — all other code keeps using the injected `EmbeddingProvider` reference unchanged. Switching closes the old inner, swaps it, re-initializes, clears all stored embeddings (incompatible vector spaces), and triggers a full rebuild.

**Tech Stack:** Kotlin, Hilt, MediaPipe `tasks-text:latest.release`, Room, Jetpack Compose, WorkManager

---

## File Map

| File | Action |
|---|---|
| `app/build.gradle.kts` | Add `tasks-text` dependency |
| `intelligence/EmbeddingModelType.kt` | **Create** — enum `GECKO` / `GEMMA` |
| `intelligence/ModelManager.kt` | Add Gemma file accessors + pref read/write |
| `intelligence/GemmaEmbeddingProvider.kt` | **Create** — MediaPipe TextEmbedder impl |
| `intelligence/SwitchableEmbeddingProvider.kt` | **Create** — delegate wrapper |
| `di/IntelligenceModule.kt` | Bind `SwitchableEmbeddingProvider` instead of `GeckoEmbeddingProvider` |
| `FocalApplication.kt` | Read pref at startup, set inner before initialize |
| `ui/settings/SettingsViewModel.kt` | Add `switchEmbeddingModel()`, update `SettingsUiState` |
| `ui/settings/SettingsScreen.kt` | Add embedding model selector UI row |

> **Note:** `NotificationDao.resetAllEmbeddings()` and `NotificationRepository.resetAllEmbeddings()` already exist — no changes needed there.

---

### Task 1: Add MediaPipe tasks-text dependency

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add dependency**

In `app/build.gradle.kts`, after the existing `tasks-genai` line (currently line 85), add:

```kotlin
implementation("com.google.mediapipe:tasks-text:latest.release")
```

- [ ] **Step 2: Sync and verify**

Run Gradle sync. Verify it resolves without conflict against the existing `tasks-genai:0.10.22`.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add mediapipe tasks-text for Gemma embedding"
```

---

### Task 2: EmbeddingModelType enum + ModelManager additions

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/EmbeddingModelType.kt`
- Modify: `app/src/main/java/com/focal/intelligence/ModelManager.kt`

- [ ] **Step 1: Create EmbeddingModelType.kt**

```kotlin
package com.focal.intelligence

enum class EmbeddingModelType { GECKO, GEMMA }
```

- [ ] **Step 2: Add Gemma file accessors and pref methods to ModelManager**

Add the following inside `class ModelManager` after the existing `geckoTokenizerFile` property (after line 52):

```kotlin
val gemmaEmbeddingModelFile: File
    get() = File(embeddingModelDir, GEMMA_MODEL_FILENAME)

val isGemmaEmbeddingAvailable: Boolean
    get() = gemmaEmbeddingModelFile.exists() && gemmaEmbeddingModelFile.length() > 50_000_000L

fun getEmbeddingModelPreference(): EmbeddingModelType {
    val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
    val raw = prefs.getString("embedding_model_type", EmbeddingModelType.GECKO.name)
    return EmbeddingModelType.entries.firstOrNull { it.name == raw } ?: EmbeddingModelType.GECKO
}

fun saveEmbeddingModelPreference(type: EmbeddingModelType) {
    val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("embedding_model_type", type.name).apply()
}
```

Add to `companion object` inside `ModelManager` (after the existing constants):

```kotlin
const val GEMMA_MODEL_FILENAME = "embeddinggemma-300M_seq512_mixed-precision.tflite"
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/EmbeddingModelType.kt \
        app/src/main/java/com/focal/intelligence/ModelManager.kt
git commit -m "feat: add EmbeddingModelType enum and ModelManager Gemma accessors"
```

---

### Task 3: GemmaEmbeddingProvider

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/GemmaEmbeddingProvider.kt`

- [ ] **Step 1: Create GemmaEmbeddingProvider.kt**

```kotlin
package com.focal.intelligence

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GemmaEmbeddingProvider(private val context: Context) : EmbeddingProvider {

    @Volatile private var embedder: TextEmbedder? = null

    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            val delegate = if (useGpu) Delegate.GPU else Delegate.CPU
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelPath)
                .setDelegate(delegate)
                .build()
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .build()
            try {
                embedder = TextEmbedder.createFromOptions(context, options)
                Log.d(TAG, "Gemma embedding model initialized (gpu=$useGpu)")
            } catch (e: Exception) {
                if (useGpu) {
                    Log.w(TAG, "GPU init failed, retrying with CPU: ${e.message}")
                    val cpuOptions = TextEmbedder.TextEmbedderOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder()
                                .setModelAssetPath(modelPath)
                                .setDelegate(Delegate.CPU)
                                .build()
                        )
                        .build()
                    embedder = TextEmbedder.createFromOptions(context, cpuOptions)
                    Log.d(TAG, "Gemma embedding model initialized (CPU fallback)")
                } else {
                    throw e
                }
            }
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val e = embedder ?: throw IllegalStateException("Gemma embedding model not initialized")
        return withContext(Dispatchers.IO) {
            val result = e.embed(text)
            val floats = result.embeddingResult().embeddings().first().floatEmbedding()!!
                .toFloatArray()
            VectorMath.l2Normalize(floats)
        }
    }

    override fun isReady(): Boolean = embedder != null

    override fun close() {
        embedder?.close()
        embedder = null
    }

    companion object {
        private const val TAG = "GemmaEmbedding"
    }
}
```

> **Note:** `TextEmbedder.createFromOptions` first param is `Context?` — pass `null` when using a file path directly (MediaPipe resolves the path absolutely).

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL with no errors on `GemmaEmbeddingProvider.kt`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/GemmaEmbeddingProvider.kt
git commit -m "feat: add GemmaEmbeddingProvider using MediaPipe TextEmbedder"
```

---

### Task 4: SwitchableEmbeddingProvider

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/SwitchableEmbeddingProvider.kt`

- [ ] **Step 1: Create SwitchableEmbeddingProvider.kt**

```kotlin
package com.focal.intelligence

class SwitchableEmbeddingProvider : EmbeddingProvider {

    @Volatile var inner: EmbeddingProvider = GeckoEmbeddingProvider()

    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) =
        inner.initialize(modelPath, tokenizerPath, useGpu)

    override suspend fun embed(text: String): FloatArray = inner.embed(text)

    override fun isReady(): Boolean = inner.isReady()

    override fun close() = inner.close()
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/SwitchableEmbeddingProvider.kt
git commit -m "feat: add SwitchableEmbeddingProvider delegate wrapper"
```

---

### Task 5: Update DI module

**Files:**
- Modify: `app/src/main/java/com/focal/di/IntelligenceModule.kt`

- [ ] **Step 1: Update provideEmbeddingProvider to return SwitchableEmbeddingProvider**

Replace the existing `provideEmbeddingProvider` function (currently lines 52–54):

```kotlin
@Provides
@Singleton
fun provideEmbeddingProvider(): EmbeddingProvider {
    return SwitchableEmbeddingProvider()
}
```

Add the import at the top of the file:

```kotlin
import com.focal.intelligence.SwitchableEmbeddingProvider
```

Remove the now-unused import:
```kotlin
import com.focal.intelligence.GeckoEmbeddingProvider  // DELETE this line
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/di/IntelligenceModule.kt
git commit -m "feat: bind SwitchableEmbeddingProvider in Hilt module"
```

---

### Task 6: Update FocalApplication startup

**Files:**
- Modify: `app/src/main/java/com/focal/FocalApplication.kt`

- [ ] **Step 1: Update the embedding initialization block**

In `FocalApplication.kt`, the embedding init block starts at line 109. Replace this entire block:

```kotlin
// Initialize embedding model (independent of LLM)
if (modelManager.isEmbeddingModelAvailable) {
    applicationScope.launch {
        try {
            val useGpu = ModelBackendPolicy.useGpuForEmbeddings(
                llmUseGpu = modelManager.getBackendPreference()
            )
            embeddingProvider.initialize(
                modelManager.geckoModelFile.absolutePath,
                modelManager.geckoTokenizerFile.absolutePath,
                useGpu
            )
            Log.d(TAG, "Embedding model initialized successfully (gpu=$useGpu)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedding model", e)
        }
    }
} else {
    Log.d(TAG, "Embedding model not found at ${modelManager.embeddingModelDir}")
}
```

With:

```kotlin
// Initialize embedding model — pick provider based on saved preference
val preferredType = modelManager.getEmbeddingModelPreference()
val (modelFile, resolvedType) = when {
    preferredType == EmbeddingModelType.GEMMA && modelManager.isGemmaEmbeddingAvailable ->
        modelManager.gemmaEmbeddingModelFile to EmbeddingModelType.GEMMA
    modelManager.isEmbeddingModelAvailable ->
        modelManager.geckoModelFile to EmbeddingModelType.GECKO
    else -> null to null
}

if (modelFile != null && resolvedType != null) {
    val switchable = embeddingProvider as SwitchableEmbeddingProvider
    switchable.inner = when (resolvedType) {
        EmbeddingModelType.GEMMA -> GemmaEmbeddingProvider(this@FocalApplication)
        EmbeddingModelType.GECKO -> GeckoEmbeddingProvider()
    }
    applicationScope.launch {
        try {
            val useGpu = ModelBackendPolicy.useGpuForEmbeddings(
                llmUseGpu = modelManager.getBackendPreference()
            )
            val tokenizerPath = if (resolvedType == EmbeddingModelType.GECKO)
                modelManager.geckoTokenizerFile.absolutePath else ""
            embeddingProvider.initialize(modelFile.absolutePath, tokenizerPath, useGpu)
            Log.d(TAG, "Embedding model initialized: $resolvedType (gpu=$useGpu)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedding model", e)
        }
    }
} else {
    Log.d(TAG, "No embedding model found at ${modelManager.embeddingModelDir}")
}
```

- [ ] **Step 2: Add required imports to FocalApplication.kt**

```kotlin
import com.focal.intelligence.EmbeddingModelType
import com.focal.intelligence.GeckoEmbeddingProvider
import com.focal.intelligence.GemmaEmbeddingProvider
import com.focal.intelligence.SwitchableEmbeddingProvider
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/FocalApplication.kt
git commit -m "feat: select embedding provider at startup based on saved preference"
```

---

### Task 7: SettingsViewModel — switch logic

**Files:**
- Modify: `app/src/main/java/com/focal/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Expand SettingsUiState**

Replace the existing `SettingsUiState` data class (lines 37–45):

```kotlin
data class SettingsUiState(
    val apps: List<AppOverride> = emptyList(),
    val snackbarMessage: String? = null,
    val useGpu: Boolean = true,
    val engineRestarting: Boolean = false,
    val isEmbeddingModelAvailable: Boolean = false,
    val isEmbeddingReady: Boolean = false,
    val isEmbeddingInitializing: Boolean = false,
    val activeEmbeddingModel: EmbeddingModelType = EmbeddingModelType.GECKO,
    val isGemmaAvailable: Boolean = false,
    val isSwitchingEmbeddingModel: Boolean = false
)
```

- [ ] **Step 2: Update init block to populate new state fields**

Replace the existing `init` block (lines 63–70):

```kotlin
init {
    _uiState.value = _uiState.value.copy(
        useGpu = modelManager.getBackendPreference(),
        isEmbeddingModelAvailable = modelManager.isEmbeddingModelAvailable,
        isEmbeddingReady = embeddingProvider.isReady(),
        activeEmbeddingModel = modelManager.getEmbeddingModelPreference(),
        isGemmaAvailable = modelManager.isGemmaEmbeddingAvailable
    )
    loadApps()
}
```

- [ ] **Step 3: Update reinitializeEmbeddings to be model-type aware**

Replace the existing `reinitializeEmbeddings` function (lines 144–156):

```kotlin
private suspend fun reinitializeEmbeddings(useGpu: Boolean) {
    val type = modelManager.getEmbeddingModelPreference()
    val modelFile = when {
        type == EmbeddingModelType.GEMMA && modelManager.isGemmaEmbeddingAvailable ->
            modelManager.gemmaEmbeddingModelFile
        modelManager.isEmbeddingModelAvailable -> modelManager.geckoModelFile
        else -> return
    }
    val tokenizerPath = if (type == EmbeddingModelType.GECKO)
        modelManager.geckoTokenizerFile.absolutePath else ""
    if (embeddingProvider.isReady()) {
        withContext(Dispatchers.IO) { embeddingProvider.close() }
    }
    embeddingProvider.initialize(modelFile.absolutePath, tokenizerPath, useGpu)
}
```

- [ ] **Step 4: Add switchEmbeddingModel function**

Add after `reinitializeEmbeddings`:

```kotlin
fun switchEmbeddingModel(type: EmbeddingModelType) {
    if (_uiState.value.isSwitchingEmbeddingModel) return
    if (type == modelManager.getEmbeddingModelPreference()) return

    if (type == EmbeddingModelType.GEMMA && !modelManager.isGemmaEmbeddingAvailable) {
        _uiState.value = _uiState.value.copy(
            snackbarMessage = "Gemma model not found in embeddings folder."
        )
        return
    }

    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isSwitchingEmbeddingModel = true)
        try {
            val switchable = embeddingProvider as SwitchableEmbeddingProvider
            withContext(Dispatchers.IO) { switchable.close() }

            switchable.inner = when (type) {
                EmbeddingModelType.GEMMA -> GemmaEmbeddingProvider(context)
                EmbeddingModelType.GECKO -> GeckoEmbeddingProvider()
            }

            val modelFile = when (type) {
                EmbeddingModelType.GEMMA -> modelManager.gemmaEmbeddingModelFile
                EmbeddingModelType.GECKO -> modelManager.geckoModelFile
            }
            val tokenizerPath = if (type == EmbeddingModelType.GECKO)
                modelManager.geckoTokenizerFile.absolutePath else ""
            val useGpu = modelManager.getBackendPreference()

            embeddingProvider.initialize(modelFile.absolutePath, tokenizerPath, useGpu)
            modelManager.saveEmbeddingModelPreference(type)

            withContext(Dispatchers.IO) {
                notificationRepository.resetAllEmbeddings()
            }
            TopicEngine.pendingFullRebuild.set(true)
            val workRequest = OneTimeWorkRequestBuilder<ClassificationWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ClassificationWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            _uiState.value = _uiState.value.copy(
                activeEmbeddingModel = type,
                isEmbeddingReady = true,
                snackbarMessage = "Switched to ${if (type == EmbeddingModelType.GEMMA) "Gemma 300M" else "Gecko"} · re-embedding..."
            )
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Failed to switch embedding model", e)
            _uiState.value = _uiState.value.copy(
                snackbarMessage = "Switch failed: ${e.message?.take(60)}"
            )
        } finally {
            _uiState.value = _uiState.value.copy(isSwitchingEmbeddingModel = false)
        }
    }
}
```

- [ ] **Step 5: Add missing imports to SettingsViewModel.kt**

```kotlin
import com.focal.intelligence.EmbeddingModelType
import com.focal.intelligence.GeckoEmbeddingProvider
import com.focal.intelligence.GemmaEmbeddingProvider
import com.focal.intelligence.SwitchableEmbeddingProvider
import com.focal.intelligence.TopicEngine
```

- [ ] **Step 6: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/focal/ui/settings/SettingsViewModel.kt
git commit -m "feat: add switchEmbeddingModel to SettingsViewModel"
```

---

### Task 8: Settings UI — embedding model selector

**Files:**
- Modify: `app/src/main/java/com/focal/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add EmbeddingModelRow composable**

Add this new composable at the bottom of `SettingsScreen.kt`, before the closing of the file:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddingModelRow(
    activeModel: EmbeddingModelType,
    isGemmaAvailable: Boolean,
    isSwitching: Boolean,
    onSelect: (EmbeddingModelType) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Embedding Model",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = activeModel == EmbeddingModelType.GECKO,
                    onClick = { if (!isSwitching) onSelect(EmbeddingModelType.GECKO) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !isSwitching
                ) {
                    Text("Gecko 110M")
                }
                SegmentedButton(
                    selected = activeModel == EmbeddingModelType.GEMMA,
                    onClick = { if (!isSwitching && isGemmaAvailable) onSelect(EmbeddingModelType.GEMMA) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !isSwitching && isGemmaAvailable
                ) {
                    Text(if (isGemmaAvailable) "Gemma 300M" else "Gemma 300M\n(not downloaded)")
                }
            }
            if (isSwitching) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Switching model · re-embedding notifications…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **Step 2: Wire EmbeddingModelRow into SettingsScreen**

In `SettingsScreen`, add a new `item { }` block after the existing `EmbeddingEngineRow` item (after line 78):

```kotlin
item {
    EmbeddingModelRow(
        activeModel = state.activeEmbeddingModel,
        isGemmaAvailable = state.isGemmaAvailable,
        isSwitching = state.isSwitchingEmbeddingModel,
        onSelect = { viewModel.switchEmbeddingModel(it) }
    )
}
```

- [ ] **Step 3: Add missing imports to SettingsScreen.kt**

```kotlin
import com.focal.intelligence.EmbeddingModelType
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Build and install on device**

Run: `./gradlew :app:installDebug`

Open the app → Settings → verify the "Embedding Model" row shows two buttons (Gecko selected, Gemma enabled if model file exists).

- [ ] **Step 6: Smoke test the switch**

Tap "Gemma 300M". Verify:
- Snackbar shows "Switched to Gemma 300M · re-embedding..."
- Logcat shows `GemmaEmbedding: Gemma embedding model initialized`
- After a few seconds, `TopicEngine: Embedding X notifications` appears in logcat
- Tap "Gecko 110M" — switches back, re-embeds

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/focal/ui/settings/SettingsScreen.kt
git commit -m "feat: add embedding model selector in developer settings"
```
