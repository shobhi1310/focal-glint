package com.focal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategoryTest {

    @Test
    fun `whatsapp is personal`() {
        assertEquals("PERSONAL", getAppCategory("com.whatsapp"))
    }

    @Test
    fun `telegram is personal`() {
        assertEquals("PERSONAL", getAppCategory("org.telegram.messenger"))
    }

    @Test
    fun `slack is work`() {
        assertEquals("WORK", getAppCategory("com.Slack"))
    }

    @Test
    fun `cred is finance`() {
        assertEquals("FINANCE", getAppCategory("com.cred.android"))
    }

    @Test
    fun `swiggy is logistics`() {
        assertEquals("LOGISTICS", getAppCategory("in.swiggy.android"))
    }

    @Test
    fun `unknown is general`() {
        assertEquals("GENERAL", getAppCategory("com.unknown.app"))
    }

    @Test
    fun `messages SMS is personal`() {
        assertEquals("PERSONAL", getAppCategory("com.google.android.apps.messaging"))
    }
}
