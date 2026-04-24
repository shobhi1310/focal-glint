package com.focal.intelligence

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import io.mockk.every
import io.mockk.mockk

class EmbeddingProviderTest {

    @Test
    fun `formatter uses clustering query prompt`() {
        assertEquals(
            "task: clustering | query: Slack/general — Release train: Canary rollout completed",
            EmbeddingTextFormatter.format(
                EmbeddingRequest(
                    title = "Slack/general — Release train",
                    text = "Canary rollout completed",
                    combinedText = "Slack/general — Release train: Canary rollout completed"
                )
            )
        )
    }

    @Test
    fun `buildInputIds truncates tokenized text to first 1024 positions including bos`() {
        val ids = EmbeddingGemmaLiteRtEmbedder("unused", useGpu = true).buildInputIds(IntArray(1200) { it + 10 })

        assertEquals(1024, ids.size)
        assertEquals(2, ids.first())
        assertEquals(1032, ids.last())
    }

    @Test
    fun `buildModelOptions selects gpu accelerator when requested`() {
        val options = EmbeddingGemmaLiteRtEmbedder("unused", useGpu = true).buildModelOptions(true)
        assertTrue(options.gpuOptions != null)
    }

    @Test
    fun `buildModelOptions selects cpu accelerator when requested`() {
        val options = EmbeddingGemmaLiteRtEmbedder("unused", useGpu = false).buildModelOptions(false)
        assertTrue(options.cpuOptions != null)
    }

    @Test
    fun `resolveTokenizerPath prefers existing explicit path`() {
        val tempDir = Files.createTempDirectory("gemma_tokenizer_explicit").toFile()
        try {
            val explicitTokenizer = File(tempDir, "custom.model").apply {
                writeText("tokenizer")
            }
            val context = mockk<Context>()

            assertEquals(
                explicitTokenizer.absolutePath,
                GemmaEmbeddingProvider.resolveTokenizerPath(
                    context = context,
                    modelPath = File(tempDir, "embeddinggemma.tflite").absolutePath,
                    tokenizerPath = explicitTokenizer.absolutePath
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveTokenizerPath falls back to gemma sibling sentencepiece model when explicit path is stale`() {
        val tempDir = Files.createTempDirectory("gemma_tokenizer_sibling").toFile()
        try {
            val modelDir = File(tempDir, "embeddings").apply { mkdirs() }
            val modelFile = File(modelDir, "embeddinggemma.tflite").apply { writeText("model") }
            File(modelDir, "sentencepiece.model").apply { writeText("legacy-tokenizer") }
            val siblingTokenizer = File(modelDir, "sentencepiece.model.2").apply { writeText("gemma-tokenizer") }
            val context = mockk<Context>()
            every { context.assets } throws AssertionError("asset fallback should not be used")

            assertEquals(
                siblingTokenizer.absolutePath,
                GemmaEmbeddingProvider.resolveTokenizerPath(
                    context = context,
                    modelPath = modelFile.absolutePath,
                    tokenizerPath = File(modelDir, "tokenizer.model").absolutePath
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildRequest uses extras thread text when available`() {
        val request = EmbeddingTextFormatter.buildRequest(
            com.focal.data.db.entity.NotificationEntity(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Family",
                content = "fallback",
                extrasJson = """
                    [
                      {"text":"Dinner at 8"},
                      {"text":"Coming"},
                      {"text":"Dinner at 8"},
                      {"text":"See you"}
                    ]
                """.trimIndent(),
                postedAt = System.currentTimeMillis(),
                category = ClassificationResult.MATTERS
            )
        )

        assertEquals("WhatsApp — Family", request?.title)
        assertEquals("Dinner at 8 | Coming | See you", request?.text)
        assertTrue(request?.combinedText?.contains("WhatsApp — Family: Dinner at 8 | Coming | See you") == true)
    }
}
