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
