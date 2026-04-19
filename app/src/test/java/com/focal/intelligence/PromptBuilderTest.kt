package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private fun notification(
        packageName: String = "com.whatsapp",
        appName: String = "WhatsApp",
        title: String = "Mom",
        content: String = "Are you coming for dinner?",
        bigText: String? = null
    ) = NotificationEntity(
        packageName = packageName,
        appName = appName,
        title = title,
        content = content,
        bigText = bigText,
        postedAt = System.currentTimeMillis()
    )

    @Test
    fun `classification prompt contains app name and sender`() {
        val prompt = PromptBuilder.buildClassificationPrompt(notification())
        assertTrue(prompt.contains("WhatsApp"))
        assertTrue(prompt.contains("Mom"))
    }

    @Test
    fun `classification prompt uses bigText when available`() {
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification(bigText = "Extended message content here")
        )
        assertTrue(prompt.contains("Extended message content here"))
    }

    @Test
    fun `classification prompt falls back to content when no bigText`() {
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification(content = "Short message", bigText = null)
        )
        assertTrue(prompt.contains("Short message"))
    }

    @Test
    fun `topic prompt includes all notification contents`() {
        val notifications = listOf(
            notification(title = "Mom", content = "Let's go to Manali"),
            notification(title = "Dad", content = "I'll book the hotel")
        )
        val prompt = PromptBuilder.buildTopicPrompt(notifications)
        assertTrue(prompt.contains("Manali"))
        assertTrue(prompt.contains("hotel"))
        assertTrue(prompt.contains("[1]"))
        assertTrue(prompt.contains("[2]"))
    }

    @Test
    fun `topic prompt truncates long content to 150 chars`() {
        val longContent = "A".repeat(300)
        val notifications = listOf(notification(content = longContent))
        val prompt = PromptBuilder.buildTopicPrompt(notifications)
        assertFalse(prompt.contains("A".repeat(300)))
        assertTrue(prompt.contains("A".repeat(150)))
    }

    @Test
    fun `topic prompt asks for TITLE and SUMMARY`() {
        val notifications = listOf(notification())
        val prompt = PromptBuilder.buildTopicPrompt(notifications)
        assertTrue(prompt.contains("TITLE:"))
        assertTrue(prompt.contains("SUMMARY:"))
    }
}
