# LiteRT-LM Upgrade + GPU/CPU Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the native crash by upgrading litertlm to 0.10.2, enable GPU inference by default, and add a CPU/GPU toggle in Settings that restarts the engine immediately.

**Architecture:** Upgrade the litertlm dependency and update LiteRtLmProvider to use the 0.10.2 API with GPU support. Add backend preference to SharedPreferences via ModelManager. Wire a segmented toggle in SettingsScreen that calls a new restart() method on InferenceProvider.

**Tech Stack:** Kotlin, Hilt, litertlm-android 0.10.2, Jetpack Compose, SharedPreferences

---

### Task 1: Upgrade litertlm and fix LiteRtLmProvider

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/focal/intelligence/InferenceProvider.kt`
- Modify: `app/src/main/java/com/focal/intelligence/LiteRtLmProvider.kt`
- Modify: `app/src/main/java/com/focal/di/IntelligenceModule.kt`

Context: The current `LiteRtLmProvider` uses litertlm 0.8.0 API which crashes with SIGABRT because the JNI library isn't loaded. Version 0.10.2 fixes this and changes the API: `Content` → `Contents`, message construction changed, `EngineConfig` now accepts `cacheDir` and `maxNumTokens`. The provider also needs a `Context` for `cacheDir`.

- [ ] **Step 1: Upgrade litertlm in build.gradle.kts**

In `app/build.gradle.kts`, change:
```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:0.8.0")
```
to:
```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")
```

- [ ] **Step 2: Update InferenceProvider interface**

Replace the entire content of `app/src/main/java/com/focal/intelligence/InferenceProvider.kt`:

```kotlin
package com.focal.intelligence

interface InferenceProvider {
    suspend fun initialize(modelPath: String, useGpu: Boolean = true)
    suspend fun restart(modelPath: String, useGpu: Boolean)
    suspend fun generate(prompt: String, maxTokens: Int = 256): String
    fun isReady(): Boolean
    fun close()
}
```

- [ ] **Step 3: Rewrite LiteRtLmProvider for 0.10.2 API**

Replace the entire content of `app/src/main/java/com/focal/intelligence/LiteRtLmProvider.kt`:

```kotlin
package com.focal.intelligence

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

class LiteRtLmProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceProvider {

    private var engine: Engine? = null
    private val mutex = Mutex()

    override suspend fun initialize(modelPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU,
                cacheDir = context.cacheDir.absolutePath,
                maxNumTokens = 8192
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            Log.d(TAG, "Engine initialized with model: $modelPath, gpu=$useGpu")
        }
    }

    override suspend fun restart(modelPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            engine?.close()
            engine = null
        }
        initialize(modelPath, useGpu)
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        val eng = engine
            ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val conversationConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 10,
                        topP = 0.95f,
                        temperature = 0.3f,
                    )
                )
                eng.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(prompt)
                    extractText(response)
                }
            }
        }
    }

    override fun isReady(): Boolean = engine != null

    override fun close() {
        engine?.close()
        engine = null
    }

    private fun extractText(message: Message): String =
        message.toString()

    companion object {
        private const val TAG = "LiteRtLmProvider"
    }
}
```

- [ ] **Step 4: Update IntelligenceModule to inject Context into LiteRtLmProvider**

In `app/src/main/java/com/focal/di/IntelligenceModule.kt`, change the `provideInferenceProvider` function:

```kotlin
@Provides
@Singleton
fun provideInferenceProvider(@ApplicationContext context: Context): InferenceProvider {
    return LiteRtLmProvider(context)
}
```

- [ ] **Step 5: Sync gradle and verify build compiles**

Run:
```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" PATH="$JAVA_HOME/bin:$PATH" ./gradlew assembleDebug 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

If you get compilation errors related to `Message` API (e.g. `message.toString()` not returning useful text), check what methods `Message` exposes in 0.10.2 by looking at the Companion app's `LlmEngine.kt` at `C:/Users/darur/AndroidStudioProjects/Companion/app/src/main/java/com/example/companion/engine/LlmEngine.kt` for reference.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts \
  app/src/main/java/com/focal/intelligence/InferenceProvider.kt \
  app/src/main/java/com/focal/intelligence/LiteRtLmProvider.kt \
  app/src/main/java/com/focal/di/IntelligenceModule.kt
git commit -m "feat: upgrade litertlm to 0.10.2, add GPU support and restart() to InferenceProvider"
```

---

### Task 2: Add backend preference to ModelManager + FocalApplication

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/ModelManager.kt`
- Modify: `app/src/main/java/com/focal/FocalApplication.kt`

Context: `ModelManager` already uses `"focal_prefs"` SharedPreferences pattern elsewhere. `FocalApplication.initializeLlmIfModelExists()` is where the engine is first initialized — it needs to read the backend pref and pass it to `initialize()`.

- [ ] **Step 1: Add backend preference helpers to ModelManager**

In `app/src/main/java/com/focal/intelligence/ModelManager.kt`, add these two functions inside the `ModelManager` class (before the `companion object`):

```kotlin
fun getBackendPreference(): Boolean {
    val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
    return prefs.getString("backend_preference", "gpu") == "gpu"
}

fun saveBackendPreference(useGpu: Boolean) {
    val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("backend_preference", if (useGpu) "gpu" else "cpu").apply()
}
```

- [ ] **Step 2: Update FocalApplication to pass useGpu to initialize()**

In `app/src/main/java/com/focal/FocalApplication.kt`, update `initializeLlmIfModelExists()` to read the backend preference. The current method is:

```kotlin
private fun initializeLlmIfModelExists() {
    val modelManager = ModelManager(this)
    modelManager.ensureModelDir()

    if (!modelManager.isModelAvailable) {
        Log.d(TAG, "Model not found at ${modelManager.modelPath}. LLM unavailable.")
        Log.d(
            TAG,
            "To enable LLM, push model via: adb push ${ModelManager.MODEL_FILENAME}" +
                " /data/data/com.focal/files/models/"
        )
        return
    }

    applicationScope.launch {
        try {
            inferenceProvider.initialize(modelManager.modelPath)
            Log.d(TAG, "LLM engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LLM engine", e)
        }
    }
}
```

Replace it with:

```kotlin
private fun initializeLlmIfModelExists() {
    val modelManager = ModelManager(this)
    modelManager.ensureModelDir()

    if (!modelManager.isModelAvailable) {
        Log.d(TAG, "Model not found at ${modelManager.modelPath}. LLM unavailable.")
        Log.d(
            TAG,
            "To enable LLM, push model via: adb push ${ModelManager.MODEL_FILENAME}" +
                " /data/data/com.focal/files/models/"
        )
        return
    }

    val useGpu = modelManager.getBackendPreference()
    applicationScope.launch {
        try {
            inferenceProvider.initialize(modelManager.modelPath, useGpu)
            Log.d(TAG, "LLM engine initialized successfully (gpu=$useGpu)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LLM engine", e)
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/ModelManager.kt \
  app/src/main/java/com/focal/FocalApplication.kt
git commit -m "feat: add backend preference to ModelManager, pass to engine on startup"
```

---

### Task 3: Add GPU/CPU toggle to SettingsViewModel

**Files:**
- Modify: `app/src/main/java/com/focal/ui/settings/SettingsViewModel.kt`

Context: `SettingsViewModel` already has `@ApplicationContext context` injected and `InferenceProvider` is a singleton managed by Hilt. It needs `InferenceProvider` and `ModelManager` injected to call `restart()`.

- [ ] **Step 1: Inject InferenceProvider and ModelManager, add useGpu state**

Replace the entire content of `app/src/main/java/com/focal/ui/settings/SettingsViewModel.kt`:

```kotlin
package com.focal.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.worker.ClassificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppOverride(
    val packageName: String,
    val appName: String,
    val notificationCount: Int,
    val systemDefault: String?,
    val userOverride: String?
)

data class SettingsUiState(
    val apps: List<AppOverride> = emptyList(),
    val snackbarMessage: String? = null,
    val useGpu: Boolean = true,
    val engineRestarting: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val notificationRepository: NotificationRepository,
    private val inferenceProvider: InferenceProvider,
    private val modelManager: ModelManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private var debounceJob: Job? = null
    private val pendingChanges = mutableMapOf<String, String?>()

    init {
        _uiState.value = _uiState.value.copy(useGpu = modelManager.getBackendPreference())
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            val profiles = notificationRepository.getAppProfiles().first()
            val userRules = ruleRepository.getUserOverrides()
            val systemRules = ruleRepository.getSystemDefaults()

            val userRuleMap = userRules
                .filter { it.type == "app_match" && it.app != null }
                .associateBy { it.app!! }
            val systemRuleMap = systemRules
                .filter { it.type == "app_match" && it.app != null }
                .associateBy { it.app!! }

            val apps = profiles
                .sortedByDescending { it.notificationCount }
                .map { profile ->
                    AppOverride(
                        packageName = profile.packageName,
                        appName = profile.appName,
                        notificationCount = profile.notificationCount,
                        systemDefault = systemRuleMap[profile.packageName]?.category,
                        userOverride = userRuleMap[profile.packageName]?.category
                    )
                }

            _uiState.value = _uiState.value.copy(apps = apps)
        }
    }

    fun onToggle(packageName: String, newState: String?) {
        _uiState.value = _uiState.value.copy(
            apps = _uiState.value.apps.map { app ->
                if (app.packageName == packageName) app.copy(userOverride = newState)
                else app
            }
        )

        pendingChanges[packageName] = newState

        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(3000)
            persistChanges()
        }
    }

    fun setBackendPreference(useGpu: Boolean) {
        if (_uiState.value.engineRestarting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(engineRestarting = true, useGpu = useGpu)
            modelManager.saveBackendPreference(useGpu)
            if (modelManager.isModelAvailable) {
                try {
                    inferenceProvider.restart(modelManager.modelPath, useGpu)
                } catch (e: Exception) {
                    // Engine restart failed — revert preference
                    val fallback = !useGpu
                    modelManager.saveBackendPreference(fallback)
                    _uiState.value = _uiState.value.copy(useGpu = fallback,
                        snackbarMessage = "Backend switch failed, reverted.")
                }
            }
            _uiState.value = _uiState.value.copy(engineRestarting = false)
        }
    }

    fun dismissSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    private suspend fun persistChanges() {
        val changes = pendingChanges.toMap()
        pendingChanges.clear()

        for ((packageName, category) in changes) {
            if (category != null) {
                ruleRepository.setUserOverride(packageName, category)
            } else {
                ruleRepository.clearUserOverride(packageName)
            }
        }

        val workRequest = OneTimeWorkRequestBuilder<ClassificationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ClassificationWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        _uiState.value = _uiState.value.copy(snackbarMessage = "Changes saved · refreshing digest...")

        delay(2000)
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/ui/settings/SettingsViewModel.kt
git commit -m "feat: add GPU/CPU toggle logic to SettingsViewModel"
```

---

### Task 4: Add GPU/CPU segmented toggle to SettingsScreen

**Files:**
- Modify: `app/src/main/java/com/focal/ui/settings/SettingsScreen.kt`

Context: The screen already uses `SingleChoiceSegmentedButtonRow` for app overrides — use the same pattern. Add the toggle as a new `item {}` block after the "Settings" title and before the "Setup AI Engine" row. Disable it while `engineRestarting = true`.

- [ ] **Step 1: Add BackendToggleRow composable and wire it into SettingsScreen**

Add this composable function at the bottom of `SettingsScreen.kt` (before the final closing brace of the file):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendToggleRow(
    useGpu: Boolean,
    restarting: Boolean,
    onSelect: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Inference Backend",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (restarting) "Restarting engine…" else "GPU is faster on supported devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("GPU", "CPU").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = if (index == 0) useGpu else !useGpu,
                        onClick = { if (!restarting) onSelect(index == 0) },
                        enabled = !restarting,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
```

Then in `SettingsScreen`, add this item block after the "Settings" title item and before the `SetupNavRow` item:

```kotlin
item {
    BackendToggleRow(
        useGpu = state.useGpu,
        restarting = state.engineRestarting,
        onSelect = { viewModel.setBackendPreference(it) }
    )
}
```

- [ ] **Step 2: Build to verify**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" PATH="$JAVA_HOME/bin:$PATH" ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/settings/SettingsScreen.kt
git commit -m "feat: add GPU/CPU backend toggle to SettingsScreen"
```

---

### Task 5: Install and verify on device

- [ ] **Step 1: Install**

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" PATH="$JAVA_HOME/bin:$PATH" ./gradlew installDebug 2>&1 | tail -5
```
Expected: `Installed on 1 device.`

- [ ] **Step 2: Verify no crash on model start**

Launch the app. If a model is present, check logcat for:
```
adb logcat -d | grep "LiteRtLmProvider\|FocalApp"
```
Expected: `Engine initialized with model: ... gpu=true` — no SIGABRT.

- [ ] **Step 3: Verify Settings toggle**

Open Settings. Confirm the "Inference Backend" toggle shows GPU selected by default. Tap CPU — confirm the subtitle briefly says "Restarting engine…" and the toggle is disabled during restart. Tap GPU again to switch back.
