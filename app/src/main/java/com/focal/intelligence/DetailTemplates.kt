package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import org.json.JSONObject

object DetailTemplates {

    fun detectAppType(appName: String, packageName: String): String {
        val name = appName.lowercase()
        val pkg = packageName.lowercase()

        return when {
            name.contains("cred") || pkg.contains("cred") -> "billing"
            name.contains("swiggy") || pkg.contains("swiggy") ||
                name.contains("zomato") || pkg.contains("zomato") ||
                name.contains("amazon") || pkg.contains("amazon") -> "transactional"
            name.contains("calendar") || pkg.contains("calendar") -> "calendar"
            name.contains("whatsapp") || pkg.contains("whatsapp") ||
                name.contains("telegram") || pkg.contains("telegram") ||
                name.contains("slack") || pkg.contains("slack") ||
                name.contains("discord") || pkg.contains("discord") -> "messaging"
            else -> "general"
        }
    }

    fun tryExtractDetail(
        notifications: List<NotificationEntity>,
        appType: String
    ): Pair<String?, String?> {
        return when (appType) {
            "billing" -> extractBillingDetail(notifications)
            "transactional" -> extractTransactionalDetail(notifications)
            "calendar" -> extractCalendarDetail(notifications)
            else -> Pair(null, null)
        }
    }

    private fun extractBillingDetail(
        notifications: List<NotificationEntity>
    ): Pair<String?, String?> {
        val allText = notifications.joinToString(" ") {
            (it.bigText ?: it.content) + " " + it.title
        }

        val amountRegex = Regex("[₹Rs.]+\\s*([\\d,]+\\.?\\d*)")
        val amount = amountRegex.find(allText)?.groupValues?.get(1)

        val dueDateRegex = Regex("(?:due|by|before)\\s+(\\w+\\s+\\d{1,2}(?:,?\\s*\\d{4})?)", RegexOption.IGNORE_CASE)
        val dueDate = dueDateRegex.find(allText)?.groupValues?.get(1)

        val cardRegex = Regex("(?:card|ending|xx)\\s*(?:in|with)?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
        val cardLast4 = cardRegex.find(allText)?.groupValues?.get(1)

        if (amount == null && dueDate == null && cardLast4 == null) {
            return Pair(null, null)
        }

        val json = JSONObject().apply {
            put("type", "billing")
            if (amount != null) put("amount", amount)
            if (dueDate != null) put("due_date", dueDate)
            if (cardLast4 != null) put("card_last4", cardLast4)
        }

        return Pair(json.toString(), "Pay bill")
    }

    private fun extractTransactionalDetail(
        notifications: List<NotificationEntity>
    ): Pair<String?, String?> {
        val latest = notifications.maxByOrNull { it.postedAt } ?: return Pair(null, null)
        val text = (latest.bigText ?: latest.content).lowercase()

        val statusKeywords = listOf(
            "delivered", "out for delivery", "shipped", "dispatched",
            "preparing", "confirmed", "cancelled", "refunded",
            "arriving", "picked up"
        )

        val status = statusKeywords.firstOrNull { text.contains(it) }

        if (status == null) return Pair(null, null)

        val json = JSONObject().apply {
            put("type", "transactional")
            put("status", status)
            put("latest_update", latest.content.takeCodepointSafe(100))
        }

        return Pair(json.toString(), "Track order")
    }

    private fun extractCalendarDetail(
        notifications: List<NotificationEntity>
    ): Pair<String?, String?> {
        val latest = notifications.maxByOrNull { it.postedAt } ?: return Pair(null, null)

        val eventName = latest.title.ifBlank { null }
            ?: return Pair(null, null)

        val json = JSONObject().apply {
            put("type", "calendar")
            put("event_name", eventName)
            if (latest.content.isNotBlank()) {
                put("details", latest.content.takeCodepointSafe(100))
            }
        }

        return Pair(json.toString(), "Open event")
    }
}
