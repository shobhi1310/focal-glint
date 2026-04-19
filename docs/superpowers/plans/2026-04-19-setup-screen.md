# Setup Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a step-based Setup page for notification access, model selection, model download, and engine start/stop — accessible via a "Setup AI Engine" row at the top of Settings.

**Architecture:** `ModelManager` gains `ModelVariant` enum and file/download helpers. A new `SetupViewModel` drives all engine state. `SetupScreen` renders 4 step cards. `SettingsScreen` gets a tappable nav row at the top that pushes `SetupScreen` onto the back stack.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, MockK, AndroidX Navigation, Android DownloadManager, `NotificationManagerCompat`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/focal/intelligence/ModelManager.kt` | Modify | Add `ModelVariant` enum + file helpers + `downloadModel()` |
| `app/src/test/java/com/focal/intelligence/ModelManagerTest.kt` | Create | Unit tests for file helpers |
| `app/src/main/java/com/focal/ui/setup/SetupViewModel.kt` | Create | Engine control state + actions |
| `app/src/test/java/com/focal/ui/setup/SetupViewModelTest.kt` | Create | Unit tests for SetupViewModel |
| `app/src/main/java/com/focal/ui/setup/SetupScreen.kt` | Create | 4-step UI composable |
| `app/src/main/java/com/focal/ui/navigation/Screen.kt` | Modify | Add `Setup` route |
| `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt` | Modify | Wire Setup composable route |
| `app/src/main/java/com/focal/ui/settings/SettingsScreen.kt` | Modify | Add "Setup AI Engine" nav row at top |

---

## Task 1: ModelVariant + ModelManager file helpers

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/ModelManager.kt`
- Create: `app/src/test/java/com/focal/intelligence/ModelManagerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/focal/intelligence/ModelManagerTest.kt`:

```kotlin
package com.focal.intelligence

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class ModelManagerTest {
    private lateinit var tempDir: File
    private lateinit var modelManager: ModelManager

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("focal_model_test").toFile()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        modelManager = ModelManager(context)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `modelFileFor GEMMA3_1B returns correct filename`() {
        assertEquals("gemma3-1b-it-int4.litertlm", modelManager.modelFileFor(ModelVariant.GEMMA3_1B).name)
    }

    @Test
    fun `modelFileFor GEMMA4_E2B returns correct filename`() {
        assertEquals("gemma-4-E2B-it.litertlm", modelManager.modelFileFor(ModelVariant.GEMMA4_E2B).name)
    }

    @Test
    fun `isModelAvailable variant returns false when file missing`() {
        assertFalse(modelManager.isModelAvailable(ModelVariant.GEMMA3_1B))
    }

    @Test
    fun `isModelAvailable variant returns false when file too small`() {
        val file = modelManager.modelFileFor(ModelVariant.GEMMA3_1B)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1000))
        assertFalse(modelManager.isModelAvailable(ModelVariant.GEMMA3_1B))
    }

    @Test
    fun `isModelAvailable variant returns true when file large enough`() {
        val file = modelManager.modelFileFor(ModelVariant.GEMMA3_1B)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(ModelManager.MIN_MODEL_SIZE + 1) }
        assertTrue(modelManager.isModelAvailable(ModelVariant.GEMMA3_1B))
    }

    @Test
    fun `activeVariant returns null when no models present`() {
        assertNull(modelManager.activeVariant())
    }

    @Test
    fun `activeVariant returns GEMMA3_1B when that file is present`() {
        val file = modelManager.modelFileFor(ModelVariant.GEMMA3_1B)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(ModelManager.MIN_MODEL_SIZE + 1) }
        assertEquals(ModelVariant.GEMMA3_1B, modelManager.activeVariant())
    }

    @Test
    fun `deleteModel removes the file`() {
        val file = modelManager.modelFileFor(ModelVariant.GEMMA3_1B)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(100))
        modelManager.deleteModel(ModelVariant.GEMMA3_1B)
        assertFalse(file.exists())
    }

    @Test
    fun `deleteModel does not throw when file absent`() {
        modelManager.deleteModel(ModelVariant.GEMMA3_1B) // no exception
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "com.focal.intelligence.ModelManagerTest" --info
```

Expected: FAIL — `ModelVariant` and new methods do not exist yet.

- [ ] **Step 3: Add ModelVariant enum and file helpers to ModelManager**

Replace the entire `ModelManager.kt` with:

```kotlin
package com.focal.intelligence

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

class ModelManager(private val context: Context) {

    val modelDir: File
        get() = File(context.filesDir, "models")

    // Legacy single-model API — kept for FocalApplication.initializeLlmIfModelExists()
    val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    val isModelAvailable: Boolean
        get() = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE

    val modelPath: String
        get() = modelFile.absolutePath

    fun ensureModelDir() {
        if (!modelDir.exists()) modelDir.mkdirs()
    }

    // Variant-aware API
    fun modelFileFor(variant: ModelVariant): File = File(modelDir, variant.fileName)

    fun isModelAvailable(variant: ModelVariant): Boolean {
        val file = modelFileFor(variant)
        return file.exists() && file.length() > MIN_MODEL_SIZE
    }

    fun activeVariant(): ModelVariant? =
        ModelVariant.entries.firstOrNull { isModelAvailable(it) }

    fun deleteModel(variant: ModelVariant) {
        modelFileFor(variant).delete()
    }

    suspend fun downloadModel(variant: ModelVariant, onProgress: (Int) -> Unit) {
        ensureModelDir()
        val destFile = modelFileFor(variant)

        val request = DownloadManager.Request(Uri.parse(variant.url))
            .setDestinationUri(Uri.fromFile(destFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setTitle("Downloading ${variant.displayName}...")
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )

        val dm = context.getSystemService(DownloadManager::class.java)
        val downloadId = dm.enqueue(request)

        withContext(Dispatchers.IO) {
            while (true) {
                delay(1000)
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (!cursor.moveToFirst()) { cursor.close(); continue }

                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                cursor.close()

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onProgress(100)
                        return@withContext
                    }
                    DownloadManager.STATUS_FAILED -> {
                        throw IOException("Download failed for ${variant.displayName}")
                    }
                    else -> {
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        onProgress(percent)
                    }
                }
            }
        }
    }

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val MIN_MODEL_SIZE = 100_000_000L
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.focal.intelligence.ModelManagerTest" --info
```

Expected: All 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/ModelManager.kt app/src/test/java/com/focal/intelligence/ModelManagerTest.kt
git commit -m "feat: add ModelVariant enum and file helpers to ModelManager"
```

---

## Task 2: SetupViewModel (TDD)

**Files:**
- Create: `app/src/main/java/com/focal/ui/setup/SetupViewModel.kt`
- Create: `app/src/test/java/com/focal/ui/setup/SetupViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/focal/ui/setup/SetupViewModelTest.kt`:

```kotlin
package com.focal.ui.setup

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var modelManager: ModelManager
    private lateinit var inferenceProvider: InferenceProvider

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        modelManager = mockk(relaxed = true)
        inferenceProvider = mockk(relaxed = true)
        mockkStatic(NotificationManagerCompat::class)
        every { context.packageName } returns "com.focal"
        every { NotificationManagerCompat.getEnabledListenerPackages(context) } returns emptySet()
        every { modelManager.activeVariant() } returns null
        every { inferenceProvider.isReady() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(NotificationManagerCompat::class)
    }

    private fun createViewModel() = SetupViewModel(context, modelManager, inferenceProvider)

    @Test
    fun `init sets notificationAccessGranted true when permission present`() = runTest {
        every { NotificationManagerCompat.getEnabledListenerPackages(context) } returns setOf("com.focal")
        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notificationAccessGranted)
    }

    @Test
    fun `init sets notificationAccessGranted false when permission absent`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.notificationAccessGranted)
    }

    @Test
    fun `init detects active model and sets selectedModel to match`() = runTest {
        every { modelManager.activeVariant() } returns ModelVariant.GEMMA4_E2B
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(ModelVariant.GEMMA4_E2B, vm.uiState.value.activeModel)
        assertEquals(ModelVariant.GEMMA4_E2B, vm.uiState.value.selectedModel)
    }

    @Test
    fun `init detects engine running`() = runTest {
        every { inferenceProvider.isReady() } returns true
        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.engineRunning)
    }

    @Test
    fun `onModelSelected same as active only updates selectedModel without side effects`() = runTest {
        every { modelManager.activeVariant() } returns ModelVariant.GEMMA3_1B
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onModelSelected(ModelVariant.GEMMA3_1B)
        advanceUntilIdle()

        verify(exactly = 0) { inferenceProvider.close() }
        verify(exactly = 0) { modelManager.deleteModel(any()) }
        assertEquals(ModelVariant.GEMMA3_1B, vm.uiState.value.selectedModel)
    }

    @Test
    fun `onModelSelected different stops engine deletes old and clears active`() = runTest {
        every { modelManager.activeVariant() } returns ModelVariant.GEMMA3_1B
        every { inferenceProvider.isReady() } returns true
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onModelSelected(ModelVariant.GEMMA4_E2B)
        advanceUntilIdle()

        verify { inferenceProvider.close() }
        verify { modelManager.deleteModel(ModelVariant.GEMMA3_1B) }
        assertEquals(ModelVariant.GEMMA4_E2B, vm.uiState.value.selectedModel)
        assertNull(vm.uiState.value.activeModel)
        assertFalse(vm.uiState.value.engineRunning)
    }

    @Test
    fun `onDownload sets activeModel on success`() = runTest {
        coEvery { modelManager.downloadModel(any(), any()) } coAnswers {
            val onProgress = secondArg<(Int) -> Unit>()
            onProgress(50)
            onProgress(100)
        }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onDownload()
        advanceUntilIdle()

        assertNull(vm.uiState.value.downloadProgress)
        assertEquals(ModelVariant.GEMMA3_1B, vm.uiState.value.activeModel)
    }

    @Test
    fun `onDownload sets errorMessage on failure`() = runTest {
        coEvery { modelManager.downloadModel(any(), any()) } throws Exception("network error")
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onDownload()
        advanceUntilIdle()

        assertNull(vm.uiState.value.downloadProgress)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `onRedownload stops engine deletes file and redownloads`() = runTest {
        every { modelManager.activeVariant() } returns ModelVariant.GEMMA3_1B
        every { inferenceProvider.isReady() } returns true
        coEvery { modelManager.downloadModel(any(), any()) } just Runs
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onRedownload()
        advanceUntilIdle()

        verify { inferenceProvider.close() }
        verify { modelManager.deleteModel(ModelVariant.GEMMA3_1B) }
        coVerify { modelManager.downloadModel(ModelVariant.GEMMA3_1B, any()) }
    }

    @Test
    fun `onStartEngine sets engineRunning true`() = runTest {
        every { modelManager.activeVariant() } returns ModelVariant.GEMMA3_1B
        every { modelManager.modelFileFor(any()) } returns mockk { every { absolutePath } returns "/path/model.litertlm" }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onStartEngine()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.engineRunning)
        coVerify { inferenceProvider.initialize("/path/model.litertlm") }
    }

    @Test
    fun `onStopEngine closes engine and sets engineRunning false`() = runTest {
        every { inferenceProvider.isReady() } returns true
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onStopEngine()

        verify { inferenceProvider.close() }
        assertFalse(vm.uiState.value.engineRunning)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "com.focal.ui.setup.SetupViewModelTest" --info
```

Expected: FAIL — `SetupViewModel` does not exist yet.

- [ ] **Step 3: Create SetupViewModel**

Create `app/src/main/java/com/focal/ui/setup/SetupViewModel.kt`:

```kotlin
package com.focal.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val notificationAccessGranted: Boolean = false,
    val selectedModel: ModelVariant = ModelVariant.GEMMA3_1B,
    val activeModel: ModelVariant? = null,
    val downloadProgress: Int? = null,
    val engineRunning: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val inferenceProvider: InferenceProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        val active = modelManager.activeVariant()
        _uiState.value = _uiState.value.copy(
            notificationAccessGranted = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName),
            activeModel = active,
            selectedModel = active ?: _uiState.value.selectedModel,
            engineRunning = inferenceProvider.isReady()
        )
    }

    fun onOpenNotifSettings() {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    fun onModelSelected(variant: ModelVariant) {
        if (variant == _uiState.value.activeModel) {
            _uiState.value = _uiState.value.copy(selectedModel = variant)
            return
        }
        viewModelScope.launch {
            inferenceProvider.close()
            _uiState.value.activeModel?.let { modelManager.deleteModel(it) }
            _uiState.value = _uiState.value.copy(
                selectedModel = variant,
                activeModel = null,
                engineRunning = false,
                downloadProgress = null
            )
        }
    }

    fun onDownload() {
        val variant = _uiState.value.selectedModel
        viewModelScope.launch { startDownload(variant) }
    }

    fun onRedownload() {
        val variant = _uiState.value.selectedModel
        viewModelScope.launch {
            inferenceProvider.close()
            modelManager.deleteModel(variant)
            _uiState.value = _uiState.value.copy(activeModel = null, engineRunning = false)
            startDownload(variant)
        }
    }

    private suspend fun startDownload(variant: ModelVariant) {
        _uiState.value = _uiState.value.copy(downloadProgress = 0, errorMessage = null)
        try {
            modelManager.downloadModel(variant) { progress ->
                _uiState.value = _uiState.value.copy(downloadProgress = progress)
            }
            _uiState.value = _uiState.value.copy(downloadProgress = null, activeModel = variant)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                downloadProgress = null,
                errorMessage = "Download failed: ${e.message}"
            )
        }
    }

    fun onStartEngine() {
        viewModelScope.launch {
            try {
                inferenceProvider.initialize(
                    modelManager.modelFileFor(_uiState.value.selectedModel).absolutePath
                )
                _uiState.value = _uiState.value.copy(engineRunning = true, errorMessage = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to start engine: ${e.message}")
            }
        }
    }

    fun onStopEngine() {
        inferenceProvider.close()
        _uiState.value = _uiState.value.copy(engineRunning = false)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.focal.ui.setup.SetupViewModelTest" --info
```

Expected: All 11 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/focal/ui/setup/SetupViewModel.kt app/src/test/java/com/focal/ui/setup/SetupViewModelTest.kt
git commit -m "feat: add SetupViewModel with engine control and model download state"
```

---

## Task 3: SetupScreen UI

**Files:**
- Create: `app/src/main/java/com/focal/ui/setup/SetupScreen.kt`

- [ ] **Step 1: Create SetupScreen.kt**

Create `app/src/main/java/com/focal/ui/setup/SetupScreen.kt`:

```kotlin
package com.focal.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.focal.intelligence.ModelVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StepCard(
                    number = 1,
                    title = "Notification Access",
                    description = "Allow Focal to read notifications from all apps",
                    isComplete = state.notificationAccessGranted
                ) {
                    if (!state.notificationAccessGranted) {
                        Button(onClick = viewModel::onOpenNotifSettings) {
                            Text("Open Settings")
                        }
                    }
                }
            }

            item {
                StepCard(
                    number = 2,
                    title = "Select Model",
                    description = "Choose the AI model for your device",
                    isComplete = true
                ) {
                    Column {
                        ModelVariant.entries.forEach { variant ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onModelSelected(variant) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.selectedModel == variant,
                                    onClick = { viewModel.onModelSelected(variant) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = variant.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = buildString {
                                            append(variant.sizeLabel)
                                            if (state.activeModel == variant) append(" · on device")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                val modelAvailable = state.activeModel == state.selectedModel
                val isDownloading = state.downloadProgress != null

                StepCard(
                    number = 3,
                    title = "Download Model",
                    description = "Download the selected model to your device",
                    isComplete = modelAvailable
                ) {
                    Column {
                        Button(
                            onClick = viewModel::onDownload,
                            enabled = !modelAvailable && !isDownloading
                        ) {
                            Text(
                                text = when {
                                    isDownloading -> "Downloading ${state.downloadProgress}%"
                                    modelAvailable -> "Downloaded ✓"
                                    else -> "Download"
                                }
                            )
                        }
                        if (modelAvailable && !isDownloading) {
                            TextButton(onClick = viewModel::onRedownload) {
                                Text(
                                    text = "Re-download",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                        state.errorMessage?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                val modelAvailable = state.activeModel == state.selectedModel
                val isDownloading = state.downloadProgress != null

                StepCard(
                    number = 4,
                    title = "Start Engine",
                    description = "Load the AI model into memory for classification",
                    isComplete = state.engineRunning
                ) {
                    Button(
                        onClick = if (state.engineRunning) viewModel::onStopEngine else viewModel::onStartEngine,
                        enabled = modelAvailable && !isDownloading
                    ) {
                        Text(if (state.engineRunning) "Stop" else "Start")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    description: String,
    isComplete: Boolean,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Step $number: $title",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isComplete) "✓" else "PENDING",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isComplete)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/ui/setup/SetupScreen.kt
git commit -m "feat: add SetupScreen with 4-step AI engine configuration UI"
```

---

## Task 4: Wire navigation + Settings nav row

**Files:**
- Modify: `app/src/main/java/com/focal/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt`
- Modify: `app/src/main/java/com/focal/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add Setup route to Screen.kt**

Replace the full `Screen.kt` with:

```kotlin
package com.focal.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Digest : Screen("digest", "Digest", Icons.Default.Star)
    data object All : Screen("all", "All", Icons.AutoMirrored.Filled.FormatListBulleted)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object Setup : Screen("setup", "Setup", Icons.Default.Settings)
    data object TopicDetail : Screen("topic/{topicId}", "Topic Detail", Icons.Default.Star) {
        fun createRoute(topicId: String) = "topic/$topicId"
    }
}
```

- [ ] **Step 2: Wire SetupScreen in FocalNavigation.kt**

Replace the full `FocalNavigation.kt` with:

```kotlin
package com.focal.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.focal.ui.all.AllNotificationsScreen
import com.focal.ui.digest.DigestScreen
import com.focal.ui.digest.TopicDetailScreen
import com.focal.ui.settings.SettingsScreen
import com.focal.ui.setup.SetupScreen

val bottomNavItems = listOf(Screen.Digest, Screen.All, Screen.Settings)

@Composable
fun FocalNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Digest.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Digest.route) {
                DigestScreen(
                    onTopicClick = { topicId ->
                        navController.navigate(Screen.TopicDetail.createRoute(topicId))
                    }
                )
            }
            composable(Screen.All.route) {
                AllNotificationsScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToSetup = { navController.navigate(Screen.Setup.route) }
                )
            }
            composable(Screen.Setup.route) {
                SetupScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.TopicDetail.route,
                arguments = listOf(navArgument("topicId") { type = NavType.StringType })
            ) {
                TopicDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
```

- [ ] **Step 3: Add "Setup AI Engine" nav row to SettingsScreen.kt**

Replace the full `SettingsScreen.kt` with:

```kotlin
package com.focal.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToSetup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                SetupNavRow(onClick = onNavigateToSetup)
            }

            item {
                Text(
                    text = "Choose how each app is classified",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            if (state.apps.isEmpty()) {
                item {
                    Text(
                        text = "No apps seen yet. Notifications will appear here as they arrive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                items(state.apps, key = { it.packageName }) { app ->
                    AppOverrideRow(
                        app = app,
                        onToggle = { newState -> viewModel.onToggle(app.packageName, newState) }
                    )
                }
            }
        }

        state.snackbarMessage?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = message)
            }
        }
    }
}

@Composable
private fun SetupNavRow(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Setup AI Engine",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Configure model & permissions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppOverrideRow(
    app: AppOverride,
    onToggle: (String?) -> Unit
) {
    val options = listOf("Matters", "Auto", "Noise")
    val selectedIndex = when (app.userOverride) {
        "matters" -> 0
        null -> 1
        "noise" -> 2
        else -> 1
    }

    val systemHint = when (app.systemDefault) {
        "matters" -> "system: matters"
        "noise" -> "system: noise"
        else -> "system: llm"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${app.notificationCount} notifications · $systemHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == selectedIndex,
                        onClick = {
                            val newState = when (index) {
                                0 -> "matters"
                                2 -> "noise"
                                else -> null
                            }
                            onToggle(newState)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run all tests to verify nothing is broken**

```bash
./gradlew :app:test --info
```

Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/focal/ui/navigation/Screen.kt app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt app/src/main/java/com/focal/ui/settings/SettingsScreen.kt
git commit -m "feat: wire Setup route and add Setup AI Engine nav row to Settings"
```

---

## Task 5: Run full test suite and verify build

- [ ] **Step 1: Run all tests**

```bash
./gradlew :app:test --info
```

Expected: All tests PASS.

- [ ] **Step 2: Build debug APK**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. APK at `app/build/outputs/apk/debug/app-debug.apk`.
