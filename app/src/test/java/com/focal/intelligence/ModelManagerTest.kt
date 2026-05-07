package com.focal.intelligence

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
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
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("focal_model_test").toFile()
        val context = mockk<Context>(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { context.filesDir } returns tempDir
        every { context.getExternalFilesDir("models") } returns File(tempDir, "external_models")
        every { context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } just runs
        every { editor.commit() } returns true
        every { prefs.getString(any(), any()) } answers { secondArg<String?>() }
        every { prefs.getBoolean(any(), any()) } answers { secondArg<Boolean>() }
        modelManager = ModelManager(context)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `modelFileFor GEMMA4_E2B returns correct filename`() {
        assertEquals("gemma-4-E2B-it.litertlm", modelManager.modelFileFor(ModelVariant.GEMMA4_E2B).name)
    }

    @Test
    fun `GEMMA4_E2B uses 8192 max context tokens`() {
        assertEquals(8192, ModelVariant.GEMMA4_E2B.maxContextTokens)
    }

    @Test
    fun `isModelAvailable variant returns false when file missing`() {
        assertFalse(modelManager.isModelAvailable(ModelVariant.GEMMA4_E2B))
    }

    @Test
    fun `isModelAvailable variant returns false when file too small`() {
        val file = modelManager.modelFileFor(ModelVariant.GEMMA4_E2B)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1000))
        assertFalse(modelManager.isModelAvailable(ModelVariant.GEMMA4_E2B))
    }

    @Test
    fun `isModelAvailable variant returns true when file large enough`() {
        val file = modelManager.modelFileFor(ModelVariant.GEMMA4_E2B)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(ModelManager.MIN_MODEL_SIZE + 1) }
        assertTrue(modelManager.isModelAvailable(ModelVariant.GEMMA4_E2B))
    }

    @Test
    fun `isGemmaEmbeddingAvailable returns false when gemma file missing`() {
        assertFalse(modelManager.isGemmaEmbeddingAvailable)
    }

    @Test
    fun `isGemmaEmbeddingAvailable returns true when gemma model exists without tokenizer`() {
        val file = modelManager.gemmaEmbeddingModelFile
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(50_000_001L) }

        assertTrue(modelManager.isGemmaEmbeddingAvailable)
    }

    @Test
    fun `tokenizerFile points to gemma sentencepiece model in embedding directory`() {
        assertEquals(
            File(tempDir, "external_models/embeddings/sentencepiece.model.2").absolutePath,
            modelManager.tokenizerFile.absolutePath
        )
    }

    @Test
    fun `activeVariant returns null when no models present`() {
        assertNull(modelManager.activeVariant())
    }

    @Test
    fun `activeVariant returns GEMMA4_E2B when that file is present`() {
        val file = modelManager.modelFileFor(ModelVariant.GEMMA4_E2B)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(ModelManager.MIN_MODEL_SIZE + 1) }
        assertEquals(ModelVariant.GEMMA4_E2B, modelManager.activeVariant())
    }

    @Test
    fun `deleteModel removes the file`() {
        val file = modelManager.modelFileFor(ModelVariant.GEMMA4_E2B)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(100))
        modelManager.deleteModel(ModelVariant.GEMMA4_E2B)
        assertFalse(file.exists())
    }

    @Test
    fun `deleteModel does not throw when file absent`() {
        modelManager.deleteModel(ModelVariant.GEMMA4_E2B)
    }

    @Test
    fun `setEngineEnabled persists boolean flag`() {
        modelManager.setEngineEnabled(true)
        verify { editor.putBoolean("llm_engine_enabled", true) }
        verify { editor.commit() }
    }

    @Test
    fun `isEngineEnabled reads persisted boolean flag`() {
        every { prefs.getBoolean("llm_engine_enabled", false) } returns true
        assertTrue(modelManager.isEngineEnabled())
    }
}
