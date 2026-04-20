package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Optional

class GeckoEmbeddingProvider(
    private val taskType: EmbedData.TaskType = EmbedData.TaskType.SEMANTIC_SIMILARITY
) : EmbeddingProvider {

    @Volatile private var model: GeckoEmbeddingModel? = null

    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            model = GeckoEmbeddingModel(
                modelPath,
                Optional.of(tokenizerPath),
                useGpu
            )
            Log.d(TAG, "Gecko embedding model initialized (gpu=$useGpu)")
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val m = model ?: throw IllegalStateException("Embedding model not initialized")
        return withContext(Dispatchers.IO) {
            val embedData = EmbedData.create(text, taskType)
            val request = EmbeddingRequest.create(listOf(embedData))
            val future = m.getEmbeddings(request)
            val result = future.get()
            val floats = FloatArray(result.size) { result[it] }
            VectorMath.l2Normalize(floats)
        }
    }

    override fun isReady(): Boolean = model != null

    override fun close() {
        model = null
    }

    companion object {
        private const val TAG = "GeckoEmbedding"
    }
}
