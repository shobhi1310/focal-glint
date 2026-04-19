package com.focal.intelligence

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            for (className in GECKO_CLASS_CANDIDATES) {
                try {
                    val clazz = Class.forName(className)
                    val ctor = clazz.getConstructor(
                        String::class.java,
                        java.util.Optional::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                    embedder = ctor.newInstance(
                        modelPath,
                        java.util.Optional.of(tokenizerPath),
                        useGpu
                    )
                    embedMethod = clazz.getMethod("embed", String::class.java)
                    Log.d(TAG, "Gecko embedding model initialized via $className (gpu=$useGpu)")
                    return@withContext
                } catch (e: ClassNotFoundException) {
                    Log.d(TAG, "Class not found: $className, trying next candidate")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to instantiate $className", e)
                }
            }
            Log.e(TAG, "No Gecko embedding model class found in any candidate path")
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
        private val GECKO_CLASS_CANDIDATES = listOf(
            "com.google.ai.edge.localagents.rag.memory.embedding.GeckoEmbeddingModel",
            "com.google.ai.edge.localagents.rag.memory.GeckoEmbeddingModel",
            "com.google.ai.edge.localagents.rag.embedding.GeckoEmbeddingModel",
            "com.google.ai.edge.localagents.rag.GeckoEmbeddingModel"
        )
    }
}
