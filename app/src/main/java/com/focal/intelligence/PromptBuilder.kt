package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity

object PromptBuilder {

    private const val TASK_INSTRUCTION =
        "You are a notification triage assistant. You MUST call classifyNotification exactly " +
            "once for EVERY [index] in the list — no index may be skipped. Use a short " +
            "snake_case reason. Output tool calls only — no prose."

    private const val DEFAULT_CRITERIA =
        "Mark it 'matters' if a thoughtful person would want to know about it now — something " +
            "asks for their attention, response, awareness, or money. Mark it 'noise' if it " +
            "exists to pull the user into an app, sell them something, surface algorithmic " +
            "content, or repeat what they already know."

    fun buildClassificationSystemPrompt(userFocus: String?): String {
        val criteria = if (userFocus.isNullOrBlank()) {
            DEFAULT_CRITERIA
        } else {
            "The user has described what they care about:\n\"$userFocus\"\n\n" +
                "Mark 'matters' if the notification serves their stated interests or requires " +
                "their attention. Mark 'noise' if it doesn't align with what they described or " +
                "is clearly unwanted. For notifications not covered by their stated interests, " +
                "use your best judgment — personal communication and urgent alerts still matter."
        }
        return "$TASK_INSTRUCTION\n\n$criteria"
    }

    fun buildExtractionAugment(activeCategories: List<String>): String {
        val categoriesStr = activeCategories.joinToString(", ")
        return "\n\nFor every notification you marked 'matters', you MUST also call exactly one of: " +
            "an extraction tool if the notification is a clear, confident match for that tool's stated criteria, " +
            "OR noExtraction if none of the extraction tools apply. Read each tool's description carefully. " +
            "When the same real-world event appears across several notifications, call the extraction tool " +
            "once for the most informative source only and noExtraction for the rest. " +
            "Available extraction categories: $categoriesStr. Output tool calls only."
    }

    fun buildClassificationPrompt(notification: NotificationEntity): String {
        val body = (notification.bigText ?: notification.content).replace('\n', ' ').take(200)
        return "[1] App: ${notification.appName} · Title: ${notification.title} · Content: $body"
    }

    fun buildBankTransactionAugment(bankFlaggedIndices: List<Int>): String {
        if (bankFlaggedIndices.isEmpty()) return ""
        val indices = bankFlaggedIndices.joinToString(", ") { "[$it]" }
        return "\n\nNotifications at indices $indices are bank transaction SMS. " +
            "You MUST call extractBankTransaction for each of them to extract the " +
            "amount, direction (debit/credit), account number, bank name, and merchant."
    }

    fun buildBatchClassificationPrompt(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()
        notifications.forEachIndexed { i, n ->
            val body = (n.bigText ?: n.content).replace('\n', ' ').take(200)
            sb.appendLine("[${i + 1}] App: ${n.appName} · Title: ${n.title} · Content: $body")
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
