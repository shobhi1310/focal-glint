package com.focal.intelligence

import android.util.Log
import java.io.Closeable

class EmbeddingGemmaLiteRtEmbedder(
    private val modelPath: String,
    @Suppress("unused") private val useGpu: Boolean
) : Closeable {

    private val MAX_SEQ_LEN  = 1024
    private val PAD_TOKEN_ID = 0
    private val BOS_TOKEN_ID = 2
    private val PREPEND_BOS  = true

    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var tokenizer: SentencePieceTokenizer? = null

    fun initialize(tokenizerPath: String) {
        val handle = LiteRtEmbedderJni.nativeCreate(modelPath)
        if (handle == 0L) error("LiteRt embedding model failed to load: $modelPath (see logcat LiteRtEmbedJNI)")
        nativeHandle = handle
        tokenizer = SentencePieceTokenizer(tokenizerPath).also { it.initialize() }
        Log.d(TAG, "initialized (cpu-only via JNI)")
    }

    fun embed(text: String): FloatArray {
        val tok    = tokenizer     ?: error("EmbeddingGemmaLiteRtEmbedder not initialized")
        val handle = nativeHandle
        check(handle != 0L) { "EmbeddingGemmaLiteRtEmbedder not initialized" }

        val ids  = buildInputIds(tok.encode(text))
        val mask = IntArray(MAX_SEQ_LEN) { i -> if (ids[i] != PAD_TOKEN_ID) 1 else 0 }

        val raw = LiteRtEmbedderJni.nativeEmbed(handle, ids, mask)
            ?: error("LiteRt embed returned null")
        return VectorMath.l2Normalize(raw)
    }

    internal fun buildInputIds(rawIds: IntArray): IntArray {
        val withBos = if (PREPEND_BOS) {
            IntArray(rawIds.size + 1).also {
                it[0] = BOS_TOKEN_ID
                rawIds.copyInto(it, 1)
            }
        } else rawIds

        return IntArray(MAX_SEQ_LEN) { i ->
            if (i < withBos.size) withBos[i] else PAD_TOKEN_ID
        }
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            LiteRtEmbedderJni.nativeClose(handle)
        }
        tokenizer?.close()
        tokenizer = null
    }

    fun isReady(): Boolean = nativeHandle != 0L && tokenizer != null

    companion object {
        private const val TAG = "EmbeddingGemmaLiteRt"
    }
}
