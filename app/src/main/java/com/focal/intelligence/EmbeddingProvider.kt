package com.focal.intelligence

interface EmbeddingProvider {
    suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean = true)
    suspend fun embed(request: EmbeddingRequest): FloatArray
    fun isReady(): Boolean
    fun close()
}
