package com.focal.intelligence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {

    @Test
    fun `toBytes and toFloats round-trip preserves values`() {
        val original = floatArrayOf(1.0f, -0.5f, 0.123f, 0f)
        val bytes = VectorMath.toBytes(original)
        val restored = VectorMath.toFloats(bytes)
        assertArrayEquals(original, restored, 1e-6f)
    }

    @Test
    fun `l2Normalize produces unit-length vector`() {
        val v = floatArrayOf(3f, 4f)
        val n = VectorMath.l2Normalize(v)
        val length = Math.sqrt((n[0] * n[0] + n[1] * n[1]).toDouble()).toFloat()
        assertEquals(1f, length, 1e-5f)
        assertEquals(0.6f, n[0], 1e-5f)
        assertEquals(0.8f, n[1], 1e-5f)
    }

    @Test
    fun `l2Normalize handles zero vector`() {
        val v = floatArrayOf(0f, 0f, 0f)
        val n = VectorMath.l2Normalize(v)
        assertEquals(3, n.size)
    }

    @Test
    fun `dot of identical normalized vectors is 1`() {
        val v = VectorMath.l2Normalize(floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, VectorMath.dot(v, v), 1e-5f)
    }

    @Test
    fun `dot of orthogonal vectors is 0`() {
        val a = VectorMath.l2Normalize(floatArrayOf(1f, 0f))
        val b = VectorMath.l2Normalize(floatArrayOf(0f, 1f))
        assertEquals(0f, VectorMath.dot(a, b), 1e-5f)
    }

    @Test
    fun `dot of opposite vectors is -1`() {
        val a = VectorMath.l2Normalize(floatArrayOf(1f, 0f))
        val b = VectorMath.l2Normalize(floatArrayOf(-1f, 0f))
        assertEquals(-1f, VectorMath.dot(a, b), 1e-5f)
    }

    @Test
    fun `toBytes produces correct byte count`() {
        val v = floatArrayOf(1f, 2f, 3f)
        val bytes = VectorMath.toBytes(v)
        assertEquals(12, bytes.size)
    }
}
