package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity

object PromptBuilder {

    fun buildClassificationPrompt(notification: NotificationEntity): String {
        val body = (notification.bigText ?: notification.content).replace('\n', ' ').take(200)
        return "[1] Title: ${notification.title} · Content: $body"
    }

    fun buildBatchClassificationPrompt(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()
        notifications.forEachIndexed { i, n ->
            val body = (n.bigText ?: n.content).replace('\n', ' ').take(200)
            sb.appendLine("[${i + 1}] Title: ${n.title} · Content: $body")
        }
        return sb.toString().trimEnd()
    }

    fun buildTopicPrompt(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("You are generating a topic card for a notification digest app.")
        sb.appendLine("Given these notifications, produce:")
        sb.appendLine("1. TITLE: A short, action-invoking headline (3-5 words max).")
        sb.appendLine("2. SUMMARY: One sentence explaining what happened. Be specific — names, amounts, times.")
        sb.appendLine("3. ACTIONS: Up to 3 suggested next steps for the user.")
        sb.appendLine("   Format each action as: {contextual label} | {type} | {app name}")
        sb.appendLine("   Types: call, reply, open_app, view, pay, track")
        sb.appendLine("   Labels should give direction — explain WHY, not just what.")
        sb.appendLine()
        sb.appendLine("Examples:")
        sb.appendLine()
        sb.appendLine("TITLE: Mom wants Sunday lunch")
        sb.appendLine("SUMMARY: Two missed calls and a WhatsApp asking if you're bringing Maya.")
        sb.appendLine("ACTIONS:")
        sb.appendLine("- Call Mom back — she tried twice | call | Phone")
        sb.appendLine("- Reply about Sunday plans | reply | WhatsApp")
        sb.appendLine()
        sb.appendLine("TITLE: ₹44K ICICI card charge")
        sb.appendLine("SUMMARY: Rs 44,000 spent on your ICICI card at Amazon on Apr 18.")
        sb.appendLine("ACTIONS:")
        sb.appendLine("- Check if this charge was you | open_app | Messages")
        sb.appendLine("- Review your ICICI card statement | view | Gmail")
        sb.appendLine()
        sb.appendLine("TITLE: Swiggy order arriving")
        sb.appendLine("SUMMARY: Your Swiggy order from Biryani Blues is out for delivery.")
        sb.appendLine("ACTIONS:")
        sb.appendLine("- Track your delivery | track | Swiggy")
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
