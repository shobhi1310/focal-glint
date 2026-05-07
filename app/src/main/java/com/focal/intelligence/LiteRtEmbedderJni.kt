package com.focal.intelligence

object LiteRtEmbedderJni {
    init {
        System.loadLibrary("focal_intelligence")
    }

    external fun nativeCreate(modelPath: String): Long
    external fun nativeEmbed(handle: Long, inputIds: IntArray, inputMask: IntArray): FloatArray?
    external fun nativeClose(handle: Long)
}
