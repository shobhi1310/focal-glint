package com.focal.intelligence

interface InferenceProvider {
    suspend fun initialize(modelPath: String, useGpu: Boolean = true)
    suspend fun restart(modelPath: String, useGpu: Boolean)
    suspend fun generate(prompt: String, maxTokens: Int = 256): String
    fun isReady(): Boolean
    fun close()
}
