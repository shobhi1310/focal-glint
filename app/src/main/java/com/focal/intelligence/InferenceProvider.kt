package com.focal.intelligence

import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.flow.Flow

class InferenceBusyException(
    message: String = "Inference engine is busy. Please retry shortly."
) : Exception(message)

interface ConversationSession : java.io.Closeable {
    fun send(prompt: String): Flow<Message>
}

// Implementation contract: every prompt-bearing entry point (generate, generateWithTools,
// startConversation().send) must sanitize input before any JNI hop into native code.
// Java's UTF-16 strings can carry orphan surrogates and embedded NULs that survive into
// JNI's Modified UTF-8 output and crash strict UTF-8 parsers (nlohmann, etc.) with SIGABRT.
// LiteRtLmProvider routes all three paths through String.sanitizeForJni() — any future
// implementation must do the equivalent at the same boundary, never at callers.
interface InferenceProvider {
    suspend fun initialize(modelPath: String, useGpu: Boolean = true, maxContextTokens: Int = 8192)
    suspend fun restart(modelPath: String, useGpu: Boolean, maxContextTokens: Int = 8192)
    suspend fun generate(prompt: String, maxTokens: Int = 256, waitIfBusy: Boolean = true): String
    suspend fun generateWithTools(
        systemInstruction: String,
        prompt: String,
        tools: List<ToolSet>,
        waitIfBusy: Boolean = true,
        automaticToolCalling: Boolean = false
    ): Flow<Message>
    suspend fun startConversation(
        systemInstruction: String,
        tools: List<ToolSet>,
        waitIfBusy: Boolean = true
    ): ConversationSession
    fun isReady(): Boolean
    fun close()
}
