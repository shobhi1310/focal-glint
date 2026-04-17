package com.focal.intelligence

interface InferenceProvider {
    suspend fun initialize(modelPath: String)
    suspend fun generate(prompt: String, maxTokens: Int = 256): String
    fun isReady(): Boolean
    fun close()
}
