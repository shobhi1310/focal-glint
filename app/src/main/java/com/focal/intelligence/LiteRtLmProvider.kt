package com.focal.intelligence

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

class LiteRtLmProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceProvider {

    private var engine: Engine? = null
    private val mutex = Mutex()

    override suspend fun initialize(modelPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = if (useGpu) Backend.GPU() else Backend.CPU,
                cacheDir = context.cacheDir.absolutePath,
                maxNumTokens = 8192
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            Log.d(TAG, "Engine initialized with model: $modelPath, gpu=$useGpu")
        }
    }

    override suspend fun restart(modelPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            engine?.close()
            engine = null
        }
        initialize(modelPath, useGpu)
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        val eng = engine
            ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val conversationConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 10,
                        topP = 0.95f,
                        temperature = 0.3f,
                    )
                )
                eng.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(prompt)
                    extractText(response)
                }
            }
        }
    }

    override fun isReady(): Boolean = engine != null

    override fun close() {
        engine?.close()
        engine = null
    }

    private fun extractText(message: Message): String =
        message.toString()

    companion object {
        private const val TAG = "LiteRtLmProvider"
    }
}
