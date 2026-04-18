package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity

object PromptBuilder {

    fun buildClassificationPrompt(notification: NotificationEntity): String {
        val message = notification.bigText ?: notification.content
        return """Does this notification matter personally to the user, or is it generic/promotional?

Notification from ${notification.appName}:
"${notification.title}: ${message.take(200)}"

Answer with JSON only: {"matters": true, "reason": "..."} or {"matters": false, "reason": "..."}"""
    }

    fun buildNarrativePrompt(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("You are a personal assistant briefing the user about their notifications.")
        sb.appendLine("Write ONE natural sentence summarizing what happened.")
        sb.appendLine("Be specific — include names, amounts, times, places.")
        sb.appendLine("Do NOT say \"Here's a summary\" or \"Summary:\" — just state what happened.")
        sb.appendLine("Write as if telling a friend.")
        sb.appendLine()
        sb.appendLine("Notifications:")
        notifications.forEachIndexed { index, notif ->
            val content = notif.bigText ?: notif.content
            sb.appendLine("[${index + 1}] ${notif.appName} — ${notif.title}: ${content.take(150)}")
        }
        sb.appendLine()
        sb.appendLine("Write one sentence:")
        return sb.toString()
    }

    // Legacy summarization prompt — retained for Task 1 transition; Task 2 rewrites Summarizer.
    fun buildSummarizationPrompt(
        appName: String,
        conversationName: String?,
        notifications: List<NotificationEntity>
    ): String {
        val label = if (conversationName != null) "$appName — $conversationName" else appName

        val sb = StringBuilder()
        sb.appendLine("Summarize these notifications from \"$label\" in one sentence.")
        sb.appendLine("Focus on decisions, action items, and things the user needs to know.")
        sb.appendLine()

        notifications.forEachIndexed { index, notif ->
            val content = notif.bigText ?: notif.content
            sb.appendLine("[${index + 1}] ${notif.title}: \"${content.take(100)}\"")
        }

        sb.appendLine()
        sb.appendLine("Summary:")

        return sb.toString()
    }

    fun buildBriefingPrompt(storyNarratives: List<String>, noiseCount: Int): String {
        val sb = StringBuilder()
        sb.appendLine("Write a 2-3 line personal briefing for the user.")
        sb.appendLine("Mention the most important things first. Be concise and specific.")
        sb.appendLine("Do NOT use bullet points. Write natural sentences.")
        sb.appendLine()
        sb.appendLine("Today's stories:")
        storyNarratives.forEach { narrative ->
            sb.appendLine("- $narrative")
        }
        if (noiseCount > 0) {
            sb.appendLine()
            sb.appendLine("$noiseCount promotional/noise notifications were hidden.")
        }
        sb.appendLine()
        sb.appendLine("Write the briefing:")
        return sb.toString()
    }
}
