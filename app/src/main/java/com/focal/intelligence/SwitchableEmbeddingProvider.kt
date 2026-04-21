package com.focal.intelligence

class SwitchableEmbeddingProvider : EmbeddingProvider {

    @Volatile var inner: EmbeddingProvider = GeckoEmbeddingProvider()

    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) =
        inner.initialize(modelPath, tokenizerPath, useGpu)

    override suspend fun embed(text: String): FloatArray = inner.embed(text)

    override fun isReady(): Boolean = inner.isReady()

    override fun close() = inner.close()
}
