package com.focal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private fun now() = System.currentTimeMillis()

    @Test
    fun `just now for under 1 minute`() {
        assertEquals("just now", formatRelativeTime(now() - 30_000, now()))
    }

    @Test
    fun `minutes ago`() {
        assertEquals("5 min ago", formatRelativeTime(now() - 5 * 60_000, now()))
    }

    @Test
    fun `1 hour ago`() {
        assertEquals("1h ago", formatRelativeTime(now() - 60 * 60_000, now()))
    }

    @Test
    fun `several hours ago`() {
        assertEquals("3h ago", formatRelativeTime(now() - 3 * 60 * 60_000, now()))
    }

    @Test
    fun `yesterday`() {
        assertEquals("yesterday", formatRelativeTime(now() - 25 * 60 * 60_000, now()))
    }
}
