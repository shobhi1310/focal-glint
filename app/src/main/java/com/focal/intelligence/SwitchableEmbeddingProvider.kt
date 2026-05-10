package com.focal.intelligence

class SwitchableEmbeddingProvider : EmbeddingProvider {

    @Volatile var inner: EmbeddingProvider = UninitializedEmbeddingProvider

    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) =
        inner.initialize(modelPath, tokenizerPath, useGpu)

    override suspend fun embed(request: EmbeddingRequest): FloatArray = inner.embed(request)

    override fun isReady(): Boolean = inner.isReady()

    override fun close() = inner.close()
}

private object UninitializedEmbeddingProvider : EmbeddingProvider {
    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) = Unit
    override suspend fun embed(request: EmbeddingRequest): FloatArray = error("No embedding model loaded")
    override fun isReady(): Boolean = false
    override fun close() = Unit
}
