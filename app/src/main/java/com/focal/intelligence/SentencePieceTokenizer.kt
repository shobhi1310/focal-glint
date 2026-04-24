package com.focal.intelligence

import java.io.Closeable

class SentencePieceTokenizer(private val modelPath: String) : Closeable {

    @Volatile private var handle: Long = 0L

    @Synchronized
    fun initialize() {
        check(handle == 0L) { "Tokenizer already initialized" }
        handle = nativeCreate(modelPath)
    }

    fun encode(text: String): IntArray {
        val h = handle
        check(h != 0L) { "Tokenizer not initialized" }
        return nativeEncode(h, text)
    }

    @Synchronized
    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeClose(h)
        }
    }

    private external fun nativeCreate(modelPath: String): Long
    private external fun nativeEncode(handle: Long, text: String): IntArray
    private external fun nativeClose(handle: Long)

    companion object {
        init {
            System.loadLibrary("focal_intelligence")
        }
    }
}
