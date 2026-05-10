package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity

// String.take(n) cuts at UTF-16 code-unit n, which can split a surrogate pair on
// emoji or mathematical-bold characters and leave a high surrogate orphaned —
// invalid UTF-8 once JNI hands it to native code (SIGABRT in nlohmann::json::parse).
// Use this for any user-controlled text that may eventually reach the on-device LLM.
internal fun String.takeCodepointSafe(n: Int): String {
    if (length <= n) return this
    val cut = if (this[n - 1].code in 0xD800..0xDBFF) n - 1 else n
    return substring(0, cut)
}

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
        val body = (notification.bigText ?: notification.content).replace('\n', ' ').takeCodepointSafe(200)
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
            val body = (n.bigText ?: n.content).replace('\n', ' ').takeCodepointSafe(200)
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

    fun buildTopicSystemPrompt(): String =
        "You are Focal, a personal notification assistant. " +
        "Given a cluster of related notifications, generate a topic card: a title, a summary, and suggested next steps.\n\n" +

        "TITLE: 5–8 words. What happened — not what to do.\n\n" +

        "SUMMARY: 1–2 sentences. Factual. Name people, amounts, and events specifically. No filler.\n\n" +

        "ACTIONS — the most important principle:\n" +
        "An action is something the user must DO, not somewhere to go. " +
        "Start every label with a verb. Make it specific to the actual content — " +
        "include the person's name, the subject, the amount, or the event. " +
        "A generic label ('Check messages', 'Open app') is always wrong.\n\n" +

        "Fill slots greedily. Every notification in the cluster is a signal. " +
        "If different notifications call for different responses, give each its own action slot. " +
        "If the same notification has multiple things to act on, use multiple slots for it too. " +
        "Only leave a slot empty (label '', index -1) when you have exhausted all meaningful next steps.\n\n" +

        "Urgency order: the action the user should take first goes in slot 1.\n\n" +

        "App index: use the index number from the AVAILABLE APPS list — not the app name string.\n\n" +

        "Output the tool call only. No prose."

    /**
     * Builds the user-turn prompt and returns both the prompt string and the ordered
     * available apps list so the caller can construct GenerateTopicCardTool with the same order.
     */
    fun buildTopicPromptWithApps(notifications: List<NotificationEntity>): Pair<String, List<Pair<String, String>>> {
        // Build ordered list: system apps first, then topic apps (deduplicated by package)
        val seen = mutableSetOf<String>()
        val orderedApps = mutableListOf<Pair<String, String>>()
        SYSTEM_APPS.forEach { (name, pkg) ->
            if (seen.add(pkg)) orderedApps.add(name to pkg)
        }
        notifications
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .forEach { n ->
                if (seen.add(n.packageName)) orderedApps.add(n.appName to n.packageName)
            }

        val sb = StringBuilder()
        sb.appendLine("AVAILABLE APPS (use the index number, not the package name):")
        orderedApps.forEachIndexed { i, (name, pkg) ->
            sb.appendLine("[$i] $name — $pkg")
        }
        sb.appendLine()
        sb.appendLine("Notifications:")
        notifications.forEachIndexed { i, n ->
            val content = n.bigText ?: n.content
            sb.appendLine("[${i + 1}] ${n.appName} — ${n.title}: ${content.takeCodepointSafe(150)}")
        }
        sb.appendLine()
        sb.append("Call generateTopicCard now.")

        return sb.toString() to orderedApps
    }

    // Keep for backward compat — callers that don't need the apps list
    fun buildTopicPrompt(notifications: List<NotificationEntity>): String =
        buildTopicPromptWithApps(notifications).first

    private val SYSTEM_APPS = mapOf(
        "Phone" to "com.android.phone",
        "Messages" to "com.google.android.apps.messaging",
        "Chrome" to "com.android.chrome"
    )
}
