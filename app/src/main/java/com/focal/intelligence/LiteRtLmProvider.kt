package com.focal.intelligence

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject

private val THINKING_CHANNELS = listOf(ThinkingMode.thoughtChannel)

class LiteRtLmProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : InferenceProvider {

    private var engine: Engine? = null
    private var activeConversation: Conversation? = null

    // Serializes inference. tryLock() lets UI callers fail fast when busy
    // instead of stacking up behind a long-running background batch.
    private val inferenceMutex = Mutex()

    // Single thread ensures LiteRT's one-session-at-a-time constraint at the engine level.
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

    override suspend fun generate(prompt: String, maxTokens: Int, waitIfBusy: Boolean): String {
        val eng = engine
            ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        val acquired = if (waitIfBusy) {
            inferenceMutex.lock(); true
        } else {
            inferenceMutex.tryLock()
        }
        if (!acquired) throw InferenceBusyException()

        return try {
            withContext(llmDispatcher) {
                activeConversation?.let { if (it.isAlive) it.close() }
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
        } finally {
            inferenceMutex.unlock()
        }
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun generateWithTools(
        systemInstruction: String,
        prompt: String,
        tools: List<ToolSet>,
        waitIfBusy: Boolean,
        automaticToolCalling: Boolean
    ): Flow<Message> {
        val eng = engine
            ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        val acquired = if (waitIfBusy) {
            inferenceMutex.lock(); true
        } else {
            inferenceMutex.tryLock()
        }
        if (!acquired) throw InferenceBusyException()

        return channelFlow {
            try {
                withContext(llmDispatcher) {
                    activeConversation?.let { if (it.isAlive) it.close() }
                    activeConversation = null

                    Log.i(TAG, "generateWithTools: promptLen=${prompt.length} tools=${tools.size}")
                    val toolProviders = tools.map { tool(it) }
                    val toolManager = ToolManager(toolProviders)
                    val config = ConversationConfig(
                        systemInstruction = Contents.of(systemInstruction),
                        tools = toolProviders,
                        automaticToolCalling = automaticToolCalling,
                        channels = THINKING_CHANNELS
                    )
                    ExperimentalFlags.enableConversationConstrainedDecoding = true
                    val conv = try {
                        eng.createConversation(config)
                    } finally {
                        ExperimentalFlags.enableConversationConstrainedDecoding = false
                    }
                    activeConversation = conv
                    try {
                        conv.sendMessageAsync(prompt).collect { message ->
                            // Manual dispatch only needed when automaticToolCalling=false.
                            // When true, the framework executes tool calls internally.
                            if (!automaticToolCalling) {
                                message.toolCalls.forEach { call ->
                                    toolManager.execute(call.name, call.arguments.toJsonObject())
                                }
                            }
                            send(message)
                        }
                    } finally {
                        if (conv.isAlive) conv.close()
                        activeConversation = null
                    }
                }
            } finally {
                inferenceMutex.unlock()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun startConversation(
        systemInstruction: String,
        tools: List<ToolSet>,
        waitIfBusy: Boolean
    ): ConversationSession {
        val eng = engine
            ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        val acquired = if (waitIfBusy) {
            inferenceMutex.lock(); true
        } else {
            inferenceMutex.tryLock()
        }
        if (!acquired) throw InferenceBusyException()

        return withContext(llmDispatcher) {
            activeConversation?.let { if (it.isAlive) it.close() }
            activeConversation = null

            val toolProviders = tools.map { tool(it) }
            val toolManager = ToolManager(toolProviders)
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                tools = toolProviders,
                automaticToolCalling = false,
                channels = THINKING_CHANNELS
            )
            ExperimentalFlags.enableConversationConstrainedDecoding = true
            val conv = try {
                eng.createConversation(config)
            } finally {
                ExperimentalFlags.enableConversationConstrainedDecoding = false
            }
            activeConversation = conv
            Log.i(TAG, "startConversation: tools=${tools.size}")

            object : ConversationSession {
                override fun send(prompt: String): Flow<Message> = channelFlow {
                    withContext(llmDispatcher) {
                        Log.i(TAG, "conversation.send: promptLen=${prompt.length}")
                        conv.sendMessageAsync(prompt).collect { message ->
                            message.toolCalls.forEach { call ->
                                toolManager.execute(call.name, call.arguments.toJsonObject())
                            }
                            send(message)
                        }
                    }
                }

                override fun close() {
                    try { conv.cancelProcess() } catch (_: Exception) {}
                    try { if (conv.isAlive) conv.close() } catch (_: Exception) {}
                    activeConversation = null
                    inferenceMutex.unlock()
                    Log.i(TAG, "ConversationSession closed")
                }
            }
        }
    }

    override fun isReady(): Boolean = engine != null

    override fun close() {
        Log.i(TAG, "Closing engine. Active conversation will be dropped immediately if present.")
        // Cancel any ongoing decode first so the JNI worker thread stops before we tear down
        // the engine. Without this, engine?.close() races with RunDecodeAsync → SIGSEGV.
        activeConversation?.let {
            try { it.cancelProcess() } catch (_: Exception) {}
            try { if (it.isAlive) it.close() } catch (_: Exception) {}
        }
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

// ToolCall.arguments values come from LiteRT-LM's JsonObject.toMap() which converts
// JsonPrimitive(number) → Number, JsonPrimitive(string) → String, etc. We must
// reconstruct proper JsonElement types so ReflectionTool's isNumber/isString checks pass.
private fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject().also { obj ->
        forEach { (k, v) -> obj.add(k, v.toJsonElement()) }
    }

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull.INSTANCE
    is JsonElement -> this
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    else -> JsonPrimitive(this.toString())
}
