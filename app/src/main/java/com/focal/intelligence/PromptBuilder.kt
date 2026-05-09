package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity

object PromptBuilder {

    private const val TASK_INSTRUCTION =
        "You are a notification triage assistant. You MUST call classifyNotification exactly " +
            "once for EVERY [index] in the list — no index may be skipped. " +
            "The category parameter MUST be exactly 'matters' or 'noise' — never any other word. " +
            "Use a short snake_case reason. Output tool calls only — no prose."

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
        return "\n\nEXTRACTION IS A SEPARATE PHASE — do not mix it with classification. " +
            "During classification, classifyNotification.category must ONLY be 'matters' or 'noise'. " +
            "Never use extraction category names ($categoriesStr) as a classifyNotification category. " +
            "Extraction happens only after classification is complete and you are asked for an extraction pass. " +
            "For every notification you marked 'matters', you MUST then call at least one of: " +
            "one or more extraction tools if the notification clearly matches their stated criteria, " +
            "OR noExtraction if none of the extraction tools apply. Do not call noExtraction when any extraction tool applies. " +
            "Read each tool's description carefully. " +
            "When the same real-world event appears across several notifications, call the extraction tool " +
            "once for the most informative source only and noExtraction for the rest. Output tool calls only."
    }

    fun buildClassificationPassPrompt(batchPrompt: String): String {
        return "Classify only in this turn. Call classifyNotification exactly once for every index. " +
            "category must be exactly 'matters' or 'noise' — no other value. " +
            "Do not call extraction tools or noExtraction in this turn.\n\n$batchPrompt"
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

    fun buildExtractionPassPrompt(mattersIndices: List<Int>, bankIndices: List<Int>): String {
        val indicesStr = mattersIndices.joinToString(", ") { "[$it]" }
        val sb = StringBuilder(
            "Notifications at indices $indicesStr were marked 'matters'. " +
            "For each one call at least one tool: one or more extraction tools if it clearly matches, " +
            "or noExtraction only if none apply."
        )
        if (bankIndices.isNotEmpty()) {
            val bankStr = bankIndices.joinToString(", ") { "[$it]" }
            sb.append(" Notifications $bankStr are bank SMS — you MUST call extractBankTransaction for those.")
        }
        return sb.toString()
    }

    fun buildTopicPrompt(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()

        // Build AVAILABLE APPS: topic apps override system apps if same name
        val allApps = buildMap<String, String> {
            SYSTEM_APPS.forEach { (name, pkg) -> put(name, pkg) }
            notifications
                .filter { it.packageName.isNotBlank() }
                .distinctBy { it.packageName }
                .forEach { put(it.appName, it.packageName) }
        }

        sb.appendLine("AVAILABLE APPS — only use these for actions:")
        allApps.forEach { (name, pkg) -> sb.appendLine("$name → $pkg") }
        sb.appendLine()

        sb.appendLine("Notifications:")
        notifications.forEachIndexed { index, notif ->
            val content = notif.bigText ?: notif.content
            sb.appendLine("[${index + 1}] ${notif.appName} — ${notif.title}: ${content.take(150)}")
        }
        sb.appendLine()

        sb.appendLine("You are Focal, a personal assistant. Based on ONLY what happened above:")
        sb.appendLine()
        sb.appendLine("1. Write a short TITLE (5–8 words) summarising what happened.")
        sb.appendLine("2. Write a SUMMARY (1–2 sentences) — factual, specific (names, amounts, times), no fluff.")
        sb.appendLine("3. Write up to 3 ACTIONS — the most important things the user should do right now.")
        sb.appendLine("   - Be direct and assertive. \"Reply to Mom\" not \"Check WhatsApp\".")
        sb.appendLine("   - Reference what specifically happened. \"Review Alice's PR\" not \"Open Teams\".")
        sb.appendLine("   - Order by urgency. Most important first.")
        sb.appendLine("   - Format: {label} | {type} | {package_name}")
        sb.appendLine("   - Only use package names from AVAILABLE APPS above.")
        sb.appendLine("   - Types: call, reply, open_app, view, pay, track")
        sb.appendLine()
        sb.appendLine("TITLE:")

        return sb.toString()
    }

    private val SYSTEM_APPS = mapOf(
        "Phone" to "com.android.phone",
        "Messages" to "com.google.android.apps.messaging",
        "Chrome" to "com.android.chrome"
    )
}
