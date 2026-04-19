package com.focal.intelligence

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object VectorMath {

    fun toBytes(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { bb ->
            v.forEach(bb::putFloat)
        }.array()

    fun toFloats(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { bb.float }
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var ss = 0f
        for (x in v) ss += x * x
        val inv = 1f / sqrt(ss).coerceAtLeast(1e-8f)
        return FloatArray(v.size) { v[it] * inv }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
