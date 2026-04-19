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
        modelManager.deleteModel(ModelVariant.GEMMA3_1B)
    }
}
