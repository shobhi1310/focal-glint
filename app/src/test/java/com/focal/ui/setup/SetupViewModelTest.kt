package com.focal.ui.setup

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.ModelVariant
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var modelManager: ModelManager
    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var workManager: WorkManager

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
        every { modelManager.getSelectedVariant() } returns null
        every { modelManager.saveSelectedVariant(any()) } just Runs
        every { modelManager.isEngineEnabled() } returns false
        every { modelManager.setEngineEnabled(any()) } just Runs
        every { modelManager.isModelAvailable(any()) } returns false
        every { modelManager.getBackendPreference() } returns true
        every { modelManager.isEmbeddingModelAvailable } returns false
        every { inferenceProvider.isReady() } returns false

        mockkStatic(WorkManager::class)
        workManager = mockk(relaxed = true)
        every { WorkManager.getInstance(any()) } returns workManager
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList<WorkInfo>())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(NotificationManagerCompat::class)
        unmockkStatic(WorkManager::class)
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
    fun `init restores persisted selected model even when not active`() = runTest {
        every { modelManager.getSelectedVariant() } returns ModelVariant.GEMMA4_E2B
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(ModelVariant.GEMMA4_E2B, vm.uiState.value.selectedModel)
        assertNull(vm.uiState.value.activeModel)
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
        every { modelManager.isModelAvailable(ModelVariant.GEMMA3_1B) } returns true
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onModelSelected(ModelVariant.GEMMA3_1B)
        advanceUntilIdle()
        verify(exactly = 0) { inferenceProvider.close() }
        verify { modelManager.saveSelectedVariant(ModelVariant.GEMMA3_1B) }
        assertEquals(ModelVariant.GEMMA3_1B, vm.uiState.value.selectedModel)
    }

    @Test
    fun `onModelSelected different stops engine persists selection and clears active`() = runTest {
        every { modelManager.activeVariant() } returns ModelVariant.GEMMA3_1B
        every { modelManager.isModelAvailable(ModelVariant.GEMMA4_E2B) } returns false
        every { inferenceProvider.isReady() } returns true
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onModelSelected(ModelVariant.GEMMA4_E2B)
        advanceUntilIdle()
        verify { inferenceProvider.close() }
        verify { modelManager.saveSelectedVariant(ModelVariant.GEMMA4_E2B) }
        verify { modelManager.setEngineEnabled(false) }
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
        verify { modelManager.setEngineEnabled(false) }
        coVerify { modelManager.downloadModel(ModelVariant.GEMMA3_1B, any()) }
    }

    @Test
    fun `onStartEngine uses selected backend and sets engineRunning true`() = runTest {
        every { modelManager.activeVariant() } returns ModelVariant.GEMMA3_1B
        every { modelManager.getBackendPreference() } returns false
        every { modelManager.modelFileFor(any()) } returns mockk { every { absolutePath } returns "/path/model.litertlm" }
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onStartEngine()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.engineRunning)
        verify { modelManager.setEngineEnabled(true) }
        coVerify {
            inferenceProvider.initialize(
                "/path/model.litertlm",
                false,
                ModelVariant.GEMMA3_1B.maxContextTokens
            )
        }
    }

    @Test
    fun `onStopEngine closes engine and sets engineRunning false`() = runTest {
        every { inferenceProvider.isReady() } returns true
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onStopEngine()
        advanceUntilIdle()
        verify { inferenceProvider.close() }
        verify { modelManager.setEngineEnabled(false) }
        assertFalse(vm.uiState.value.engineRunning)
    }
}
