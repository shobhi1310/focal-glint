package com.focal.intelligence

import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.flow.Flow

class InferenceBusyException(
    message: String = "Inference engine is busy. Please retry shortly."
) : Exception(message)

interface InferenceProvider {
    suspend fun initialize(modelPath: String, useGpu: Boolean = true, maxContextTokens: Int = 8192)
    suspend fun restart(modelPath: String, useGpu: Boolean, maxContextTokens: Int = 8192)
    suspend fun generate(prompt: String, maxTokens: Int = 256, waitIfBusy: Boolean = true): String
    suspend fun generateWithTools(
        systemInstruction: String,
        prompt: String,
        tools: List<ToolSet>,
        waitIfBusy: Boolean = true
    ): Flow<Message>
    fun isReady(): Boolean
    fun close()
}
