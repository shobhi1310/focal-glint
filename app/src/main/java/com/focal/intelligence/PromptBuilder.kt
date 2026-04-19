package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity

object PromptBuilder {

    fun buildClassificationPrompt(notification: NotificationEntity): String {
        val message = notification.bigText ?: notification.content
        return "App: ${notification.appName}\nTitle: ${notification.title}\nContent: ${message.take(200)}"
    }

    fun buildTopicPrompt(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("You are generating a topic card for a notification digest app.")
        sb.appendLine("Given these notifications, produce:")
        sb.appendLine("1. TITLE: A short, action-invoking headline (3-5 words max). Focus on what matters to the user.")
        sb.appendLine("2. SUMMARY: One sentence explaining what happened. Be specific — names, amounts, times.")
        sb.appendLine()
        sb.appendLine("Examples:")
        sb.appendLine("TITLE: Mom wants Sunday lunch")
        sb.appendLine("SUMMARY: Two missed calls and a WhatsApp asking if you're bringing Maya.")
        sb.appendLine()
        sb.appendLine("TITLE: ₹44K ICICI card charge")
        sb.appendLine("SUMMARY: Rs 44,000 spent on your ICICI card at Amazon on Apr 18.")
        sb.appendLine()
        sb.appendLine("Notifications:")
        notifications.forEachIndexed { index, notif ->
            val content = notif.bigText ?: notif.content
            sb.appendLine("[${index + 1}] ${notif.appName} — ${notif.title}: ${content.take(150)}")
        }
        sb.appendLine()
        sb.appendLine("TITLE:")
        return sb.toString()
    }
}
