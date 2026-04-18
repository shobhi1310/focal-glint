package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LiteRtLmProvider : InferenceProvider {

    private var engine: Engine? = null
    private val mutex = Mutex()

    override suspend fun initialize(modelPath: String) {
        withContext(Dispatchers.IO) {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU,
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            Log.d(TAG, "Engine initialized with model: $modelPath")
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        val eng = engine
            ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val conversationConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 10,
                        topP = 0.95,
                        temperature = 0.3,
                    )
                )
                eng.createConversation(conversationConfig).use { conversation ->
                    val inputMessage = Message.of(prompt)
                    val response = conversation.sendMessage(inputMessage)
                    extractText(response)
                }
            }
        }
    }

    override fun isReady(): Boolean {
        return engine != null
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private fun extractText(message: Message): String {
        return message.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
    }

    companion object {
        private const val TAG = "LiteRtLmProvider"
    }
}
