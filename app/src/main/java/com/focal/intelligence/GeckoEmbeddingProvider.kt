package com.focal.intelligence

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device embedding provider using the Gecko embedding model via AI Edge RAG SDK.
 *
 * Uses reflection to load the GeckoEmbeddingModel class so that compilation succeeds
 * even when the exact class path shifts between SDK releases. If the class is not found
 * at runtime the provider remains in a non-ready state.
 */
class GeckoEmbeddingProvider : EmbeddingProvider {

    private var embedder: Any? = null
    private var embedMethod: java.lang.reflect.Method? = null

    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                val clazz = Class.forName(GECKO_CLASS)
                val ctor = clazz.getConstructor(
                    String::class.java,
                    java.util.Optional::class.java,
                    Boolean::class.javaPrimitiveType
                )
                embedder = ctor.newInstance(modelPath, java.util.Optional.of(tokenizerPath), useGpu)
                embedMethod = clazz.getMethod("embed", String::class.java)
                Log.d(TAG, "Gecko embedding model initialized (gpu=$useGpu)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Gecko embedding model via reflection", e)
                embedder = null
                embedMethod = null
            }
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val model = embedder
            ?: throw IllegalStateException("Embedding model not initialized")
        val method = embedMethod
            ?: throw IllegalStateException("Embed method not resolved")
        return withContext(Dispatchers.IO) {
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(model, text) as FloatArray
            VectorMath.l2Normalize(result)
        }
    }

    override fun isReady(): Boolean = embedder != null && embedMethod != null

    override fun close() {
        embedder = null
        embedMethod = null
    }

    companion object {
        private const val TAG = "GeckoEmbedding"
        private const val GECKO_CLASS =
            "com.google.ai.edge.localagents.rag.memory.GeckoEmbeddingModel"
    }
}
