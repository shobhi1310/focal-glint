package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class DetailTemplatesTest {

    private fun notification(
        packageName: String = "com.example",
        appName: String = "Example",
        title: String = "Title",
        content: String = "Content",
        bigText: String? = null
    ) = NotificationEntity(
        packageName = packageName,
        appName = appName,
        title = title,
        content = content,
        bigText = bigText,
        postedAt = System.currentTimeMillis()
    )

    // --- detectAppType tests ---

    @Test
    fun `detectAppType identifies CRED as billing`() {
        assertEquals("billing", DetailTemplates.detectAppType("CRED", "com.dreamplug.androidapp"))
    }

    @Test
    fun `detectAppType identifies CRED by package name`() {
        assertEquals("billing", DetailTemplates.detectAppType("SomeApp", "com.cred.app"))
    }

    @Test
    fun `detectAppType identifies Swiggy as transactional`() {
        assertEquals("transactional", DetailTemplates.detectAppType("Swiggy", "in.swiggy.android"))
    }

    @Test
    fun `detectAppType identifies Zomato as transactional`() {
        assertEquals("transactional", DetailTemplates.detectAppType("Zomato", "com.application.zomato"))
    }

    @Test
    fun `detectAppType identifies Amazon as transactional`() {
        assertEquals("transactional", DetailTemplates.detectAppType("Amazon", "com.amazon.mShop.android"))
    }

    @Test
    fun `detectAppType identifies WhatsApp as messaging`() {
        assertEquals("messaging", DetailTemplates.detectAppType("WhatsApp", "com.whatsapp"))
    }

    @Test
    fun `detectAppType identifies Telegram as messaging`() {
        assertEquals("messaging", DetailTemplates.detectAppType("Telegram", "org.telegram.messenger"))
    }

    @Test
    fun `detectAppType identifies Slack as messaging`() {
        assertEquals("messaging", DetailTemplates.detectAppType("Slack", "com.Slack"))
    }

    @Test
    fun `detectAppType identifies Discord as messaging`() {
        assertEquals("messaging", DetailTemplates.detectAppType("Discord", "com.discord"))
    }

    @Test
    fun `detectAppType identifies Calendar as calendar`() {
        assertEquals("calendar", DetailTemplates.detectAppType("Google Calendar", "com.google.android.calendar"))
    }

    @Test
    fun `detectAppType returns general for unknown app`() {
        assertEquals("general", DetailTemplates.detectAppType("MyApp", "com.example.myapp"))
    }

    // --- tryExtractDetail tests ---

    @Test
    fun `billing template extracts amount and card from notification text`() {
        val notifs = listOf(
            notification(
                packageName = "com.dreamplug.androidapp",
                appName = "CRED",
                title = "HDFC Card",
                content = "Your HDFC card ending 4521 bill of Rs 15,221 is due May 6"
            )
        )

        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(notifs, "billing")

        assertNotNull(detailJson)
        assertNotNull(actionLabel)
        assertEquals("Pay bill", actionLabel)

        val json = JSONObject(detailJson!!)
        assertEquals("billing", json.getString("type"))
        assertEquals("15,221", json.getString("amount"))
        assertEquals("4521", json.getString("card_last4"))
    }

    @Test
    fun `billing template extracts rupee symbol amount`() {
        val notifs = listOf(
            notification(
                content = "Pay ₹5,000 for your credit card bill",
                bigText = "Pay ₹5,000 for your credit card bill due May 10"
            )
        )

        val (detailJson, _) = DetailTemplates.tryExtractDetail(notifs, "billing")

        assertNotNull(detailJson)
        val json = JSONObject(detailJson!!)
        assertEquals("5,000", json.getString("amount"))
    }

    @Test
    fun `billing template extracts due date`() {
        val notifs = listOf(
            notification(
                content = "Bill payment due May 6 for HDFC card"
            )
        )

        val (detailJson, _) = DetailTemplates.tryExtractDetail(notifs, "billing")

        assertNotNull(detailJson)
        val json = JSONObject(detailJson!!)
        assertEquals("May 6", json.getString("due_date"))
    }

    @Test
    fun `delivery template extracts status`() {
        val notifs = listOf(
            notification(
                packageName = "in.swiggy.android",
                appName = "Swiggy",
                title = "Order Update",
                content = "Your order has been delivered"
            )
        )

        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(notifs, "transactional")

        assertNotNull(detailJson)
        assertEquals("Track order", actionLabel)

        val json = JSONObject(detailJson!!)
        assertEquals("transactional", json.getString("type"))
        assertEquals("delivered", json.getString("status"))
    }

    @Test
    fun `transactional template detects out for delivery status`() {
        val notifs = listOf(
            notification(
                content = "Your food is out for delivery"
            )
        )

        val (detailJson, _) = DetailTemplates.tryExtractDetail(notifs, "transactional")

        assertNotNull(detailJson)
        val json = JSONObject(detailJson!!)
        assertEquals("out for delivery", json.getString("status"))
    }

    @Test
    fun `returns null for messaging app type`() {
        val notifs = listOf(
            notification(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                content = "Hey, how are you?"
            )
        )

        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(notifs, "messaging")

        assertNull(detailJson)
        assertNull(actionLabel)
    }

    @Test
    fun `returns null for general app type`() {
        val notifs = listOf(
            notification(content = "Some notification")
        )

        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(notifs, "general")

        assertNull(detailJson)
        assertNull(actionLabel)
    }

    @Test
    fun `calendar template extracts event name`() {
        val notifs = listOf(
            notification(
                title = "Team Standup",
                content = "In 15 minutes"
            )
        )

        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(notifs, "calendar")

        assertNotNull(detailJson)
        assertEquals("Open event", actionLabel)

        val json = JSONObject(detailJson!!)
        assertEquals("calendar", json.getString("type"))
        assertEquals("Team Standup", json.getString("event_name"))
    }

    @Test
    fun `billing returns null when no extractable info`() {
        val notifs = listOf(
            notification(content = "Welcome to our app!")
        )

        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(notifs, "billing")

        assertNull(detailJson)
        assertNull(actionLabel)
    }

    @Test
    fun `transactional returns null when no status keyword found`() {
        val notifs = listOf(
            notification(content = "Thank you for using our service")
        )

        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(notifs, "transactional")

        assertNull(detailJson)
        assertNull(actionLabel)
    }
}
