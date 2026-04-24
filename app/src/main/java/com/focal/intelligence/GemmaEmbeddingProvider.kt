package com.focal.intelligence

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GemmaEmbeddingProvider(
    private val context: Context
) : EmbeddingProvider {

    @Volatile private var embedder: EmbeddingGemmaLiteRtEmbedder? = null

    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            embedder?.close()
            val resolvedTokenizerPath = resolveTokenizerPath(context, modelPath, tokenizerPath)
            EmbeddingGemmaLiteRtEmbedder(modelPath, useGpu).also {
                it.initialize(resolvedTokenizerPath)
                embedder = it
            }
            Log.d(TAG, "EmbeddingGemma initialized via LiteRT (gpu=$useGpu)")
        }
    }

    override suspend fun embed(request: EmbeddingRequest): FloatArray {
        return withContext(Dispatchers.IO) {
            val e = embedder ?: error("GemmaEmbeddingProvider not initialized")
            val formatted = EmbeddingTextFormatter.format(request)
            e.embed(formatted)
        }
    }

    override fun isReady(): Boolean = embedder?.isReady() == true

    override fun close() {
        embedder?.close()
        embedder = null
        Log.d(TAG, "EmbeddingGemma stopped (isReady=${isReady()})")
    }

    companion object {
        private const val TAG = "GemmaEmbedding"
        private const val GEMMA_SENTENCEPIECE_FILENAME = "sentencepiece.model.2"
        private const val LEGACY_TOKENIZER_FILENAME = "tokenizer.model"

        internal fun resolveTokenizerPath(
            context: Context,
            modelPath: String,
            tokenizerPath: String
        ): String {
            val explicitTokenizer = tokenizerPath
                .takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.exists() }
            if (explicitTokenizer != null) return explicitTokenizer.absolutePath

            val modelDir = File(modelPath).parentFile
            val siblingTokenizer = listOf(GEMMA_SENTENCEPIECE_FILENAME, LEGACY_TOKENIZER_FILENAME)
                .asSequence()
                .mapNotNull { name -> modelDir?.let { File(it, name) } }
                .firstOrNull { it.exists() }
            if (siblingTokenizer != null) return siblingTokenizer.absolutePath

            return ModelAssetManager.ensureTokenizerCopied(context)
        }

    }
}
