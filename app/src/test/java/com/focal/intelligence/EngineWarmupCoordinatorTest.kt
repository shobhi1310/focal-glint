package com.focal.intelligence

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineWarmupCoordinatorTest {

    private lateinit var context: Context
    private lateinit var modelManager: ModelManager
    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var embeddingProvider: SwitchableEmbeddingProvider
    private lateinit var coordinator: EngineWarmupCoordinator
    private var inferenceReady = false
    private var embeddingReady = false

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        modelManager = mockk(relaxed = true)
        inferenceProvider = mockk(relaxed = true)
        embeddingProvider = mockk(relaxed = true)

        every { modelManager.getSelectedVariant() } returns ModelVariant.GEMMA4_E2B
        every { modelManager.activeVariant() } returns null
        every { modelManager.isModelAvailable(ModelVariant.GEMMA4_E2B) } returns true
        every { modelManager.modelFileFor(ModelVariant.GEMMA4_E2B).absolutePath } returns "/models/gemma4.litertlm"
        every { modelManager.getBackendPreference() } returns true
        every { modelManager.isGemmaEmbeddingAvailable } returns true
        every { modelManager.gemmaEmbeddingModelFile.absolutePath } returns "/models/embedding.tflite"
        every { modelManager.tokenizerFile.absolutePath } returns "/models/tokenizer.model"
        every { inferenceProvider.isReady() } answers { inferenceReady }
        every { embeddingProvider.isReady() } answers { embeddingReady }
        coEvery { inferenceProvider.initialize(any(), any(), any()) } answers {
            inferenceReady = true
            Unit
        }
        coEvery { embeddingProvider.initialize(any(), any(), any()) } answers {
            embeddingReady = true
            Unit
        }

        coordinator = EngineWarmupCoordinator(
            context = context,
            inferenceProvider = inferenceProvider,
            embeddingProvider = embeddingProvider,
            modelManager = modelManager
        )
    }

    @Test
    fun `warmUp initializes selected llm and embedding with user backend preference`() = runTest {
        val result = coordinator.warmUp()

        assertTrue(result)
        coVerify {
            inferenceProvider.initialize(
                "/models/gemma4.litertlm",
                true,
                ModelVariant.GEMMA4_E2B.maxContextTokens
            )
        }
        coVerify {
            embeddingProvider.initialize(
                "/models/embedding.tflite",
                "/models/tokenizer.model",
                true
            )
        }
    }

    @Test
    fun `warmEmbeddings initializes embedding without initializing llm`() = runTest {
        coordinator.warmEmbeddings()

        coVerify(exactly = 0) { inferenceProvider.initialize(any(), any(), any()) }
        coVerify {
            embeddingProvider.initialize(
                "/models/embedding.tflite",
                "/models/tokenizer.model",
                true
            )
        }
    }

    @Test
    fun `warmUp reuses existing ready llm and still initializes missing embedding`() = runTest {
        inferenceReady = true

        val result = coordinator.warmUp()

        assertTrue(result)
        coVerify(exactly = 0) { inferenceProvider.initialize(any(), any(), any()) }
        coVerify { embeddingProvider.initialize("/models/embedding.tflite", "/models/tokenizer.model", true) }
    }

    @Test
    fun `warmUp returns false when no model is selected or active`() = runTest {
        every { modelManager.getSelectedVariant() } returns null
        every { modelManager.activeVariant() } returns null

        val result = coordinator.warmUp()

        assertFalse(result)
        coVerify(exactly = 0) { inferenceProvider.initialize(any(), any(), any()) }
        coVerify(exactly = 0) { embeddingProvider.initialize(any(), any(), any()) }
    }

    @Test
    fun `warmUp falls back to active model when selected model is missing`() = runTest {
        every { modelManager.getSelectedVariant() } returns ModelVariant.GEMMA4_E2B
        every { modelManager.isModelAvailable(ModelVariant.GEMMA4_E2B) } returns false
        every { modelManager.activeVariant() } returns ModelVariant.GEMMA4_E2B
        every { modelManager.modelFileFor(ModelVariant.GEMMA4_E2B).absolutePath } returns "/models/gemma4.litertlm"

        val result = coordinator.warmUp()

        assertTrue(result)
        coVerify {
            inferenceProvider.initialize(
                "/models/gemma4.litertlm",
                true,
                ModelVariant.GEMMA4_E2B.maxContextTokens
            )
        }
    }

    @Test
    fun `concurrent warmUp calls share one initialization`() = runTest {
        val first = async { coordinator.warmUp() }
        val second = async { coordinator.warmUp() }

        assertTrue(first.await())
        assertTrue(second.await())
        coVerify(exactly = 1) { inferenceProvider.initialize(any(), any(), any()) }
    }
}
