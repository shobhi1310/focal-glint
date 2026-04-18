package com.focal.intelligence

import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.CorrectionEntity
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
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification = notification(),
            appProfile = null,
            recentCorrections = emptyList(),
            correctionNotifications = emptyMap()
        )
        assertTrue(prompt.contains("WhatsApp"))
        assertTrue(prompt.contains("Mom"))
    }

    @Test
    fun `classification prompt uses bigText when available`() {
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification = notification(bigText = "Extended message content here"),
            appProfile = null,
            recentCorrections = emptyList(),
            correctionNotifications = emptyMap()
        )
        assertTrue(prompt.contains("Extended message content here"))
    }

    @Test
    fun `classification prompt falls back to content when no bigText`() {
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification = notification(content = "Short message", bigText = null),
            appProfile = null,
            recentCorrections = emptyList(),
            correctionNotifications = emptyMap()
        )
        assertTrue(prompt.contains("Short message"))
    }

    @Test
    fun `classification prompt includes app profile when available`() {
        val profile = AppProfileEntity(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            appType = "messaging",
            noiseRatio = 0.12f
        )
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification = notification(),
            appProfile = profile,
            recentCorrections = emptyList(),
            correctionNotifications = emptyMap()
        )
        assertTrue(prompt.contains("messaging app"))
        assertTrue(prompt.contains("0.12"))
    }

    @Test
    fun `classification prompt includes recent corrections`() {
        val corrNotif = notification(title = "Boss", content = "call me now")
        val correction = CorrectionEntity(
            notificationId = corrNotif.id,
            oldCategory = "informational",
            newCategory = "urgent"
        )
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification = notification(),
            appProfile = null,
            recentCorrections = listOf(correction),
            correctionNotifications = mapOf(corrNotif.id to corrNotif)
        )
        assertTrue(prompt.contains("Boss"))
        assertTrue(prompt.contains("URGENT"))
    }

    @Test
    fun `classification prompt limits corrections to 5`() {
        val corrections = (1..10).map { i ->
            val n = notification(title = "Person$i", content = "msg $i")
            CorrectionEntity(notificationId = n.id, oldCategory = "noise", newCategory = "urgent") to n
        }
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification = notification(),
            appProfile = null,
            recentCorrections = corrections.map { it.first },
            correctionNotifications = corrections.associate { it.second.id to it.second }
        )
        assertFalse(prompt.contains("Person6"))
    }

    @Test
    fun `classification prompt requests JSON response`() {
        val prompt = PromptBuilder.buildClassificationPrompt(
            notification = notification(),
            appProfile = null,
            recentCorrections = emptyList(),
            correctionNotifications = emptyMap()
        )
        assertTrue(prompt.contains("Respond in JSON only"))
        assertTrue(prompt.contains("\"category\""))
        assertTrue(prompt.contains("actionable"))
        assertTrue(prompt.contains("digest"))
    }

    @Test
    fun `summarization prompt includes all notification contents`() {
        val notifications = listOf(
            notification(title = "Mom", content = "Let's go to Manali"),
            notification(title = "Dad", content = "I'll book the hotel")
        )
        val prompt = PromptBuilder.buildSummarizationPrompt(
            appName = "WhatsApp",
            conversationName = "Family Group",
            notifications = notifications
        )
        assertTrue(prompt.contains("WhatsApp — Family Group"))
        assertTrue(prompt.contains("Manali"))
        assertTrue(prompt.contains("hotel"))
        assertTrue(prompt.contains("[1]"))
        assertTrue(prompt.contains("[2]"))
    }

    @Test
    fun `summarization prompt truncates long content to 100 chars`() {
        val longContent = "A".repeat(200)
        val notifications = listOf(notification(content = longContent))
        val prompt = PromptBuilder.buildSummarizationPrompt(
            appName = "WhatsApp",
            conversationName = null,
            notifications = notifications
        )
        assertFalse(prompt.contains("A".repeat(200)))
        assertTrue(prompt.contains("A".repeat(100)))
    }
}
