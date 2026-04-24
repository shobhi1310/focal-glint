package com.focal.intelligence

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject

private val THINKING_CHANNELS = listOf(ThinkingMode.thoughtChannel)

class LiteRtLmProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : InferenceProvider {

    private var engine: Engine? = null
    private var activeConversation: AutoCloseable? = null

    // Single thread ensures LiteRT's one-session-at-a-time constraint without locks.
    // Callers suspend (not block) while waiting — UI stays responsive.
    // Recreated on each initialize() so Stop → Start works correctly.
    private var llmDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    override suspend fun initialize(modelPath: String, useGpu: Boolean, maxContextTokens: Int) {
        withContext(Dispatchers.IO) {
            val backend: Backend = if (useGpu) Backend.GPU() else Backend.CPU()
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                cacheDir = context.cacheDir.absolutePath,
                maxNumTokens = maxContextTokens
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            // Recreate dispatcher so Stop → Start produces a fresh single thread
            llmDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            Log.d(TAG, "Engine initialized with model: $modelPath, gpu=$useGpu")
        }
    }

    override suspend fun restart(modelPath: String, useGpu: Boolean, maxContextTokens: Int) {
        withContext(Dispatchers.IO) {
            engine?.close()
            engine = null
        }
        initialize(modelPath, useGpu, maxContextTokens)
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        val eng = engine
            ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        return withContext(llmDispatcher) {
            // Close any session left open by generateWithTools before starting
            activeConversation?.close()
            activeConversation = null

            val conversationConfig = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = 64,
                    topP = 0.95,
                    temperature = 1.0,
                )
            )
            eng.createConversation(conversationConfig).use { conversation ->
                conversation.sendMessage(prompt).toString()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun generateWithTools(
        systemInstruction: String,
        prompt: String,
        tools: List<ToolSet>
    ): Flow<Message> {
        val eng = engine
            ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        return withContext(llmDispatcher) {
            // Close any previous session (generate or generateWithTools) before starting
            activeConversation?.close()
            activeConversation = null

            Log.i(TAG, "generateWithTools: promptLen=${prompt.length} tools=${tools.size}")
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                tools = tools.map { tool(it) },
                automaticToolCalling = true,
                channels = THINKING_CHANNELS
            )
            ExperimentalFlags.enableConversationConstrainedDecoding = true
            val conv = try {
                eng.createConversation(config)
            } finally {
                ExperimentalFlags.enableConversationConstrainedDecoding = false
            }
            activeConversation = conv as? AutoCloseable
            conv.sendMessageAsync(prompt)
        }
    }

    override fun isReady(): Boolean = engine != null

    override fun close() {
        Log.i(TAG, "Closing engine. Active conversation will be dropped immediately if present.")
        activeConversation?.close()
        activeConversation = null
        engine?.close()
        engine = null
        llmDispatcher.close()
        Log.i(TAG, "Engine resources released")
        Log.i(TAG, "LLM engine has stopped completely (isReady=${isReady()})")
    }

    companion object {
        private const val TAG = "LiteRtLmProvider"
    }
}
