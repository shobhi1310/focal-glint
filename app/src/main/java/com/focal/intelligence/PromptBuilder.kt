package com.focal.intelligence

import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.CorrectionEntity
import com.focal.data.db.entity.NotificationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PromptBuilder {

    fun buildClassificationPrompt(
        notification: NotificationEntity,
        appProfile: AppProfileEntity?,
        recentCorrections: List<CorrectionEntity>,
        correctionNotifications: Map<String, NotificationEntity>
    ): String {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val time = timeFormat.format(Date(notification.postedAt))
        val message = notification.bigText ?: notification.content

        val sb = StringBuilder()
        sb.appendLine("You are a notification classifier. Categorize as: urgent, actionable, digest, or noise.")
        sb.appendLine()
        sb.appendLine("- urgent: needs immediate attention (OTP, emergency, meeting right now)")
        sb.appendLine("- actionable: requires user action but not immediately (bill due, order to rate, reply needed)")
        sb.appendLine("- digest: context to catch up on later (chat messages, news, updates)")
        sb.appendLine("- noise: promotional, duplicate, or irrelevant")
        sb.appendLine()
        sb.appendLine("App: ${notification.appName} | Sender: ${notification.title} | Time: $time")
        sb.appendLine("Message: \"$message\"")

        if (recentCorrections.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("User history (recent corrections):")
            for (correction in recentCorrections.take(5)) {
                val corrNotif = correctionNotifications[correction.notificationId]
                if (corrNotif != null) {
                    sb.appendLine("- ${corrNotif.appName} from ${corrNotif.title} \"${corrNotif.content.take(50)}\" → user marked ${correction.newCategory.uppercase()}")
                }
            }
        }

        if (appProfile != null) {
            sb.appendLine()
            sb.appendLine("App profile: ${appProfile.appName} — ${appProfile.appType} app, noise_ratio: ${String.format("%.2f", appProfile.noiseRatio)}")
        }

        sb.appendLine()
        sb.appendLine("Respond in JSON only:")
        sb.appendLine("{\"category\": \"...\", \"reason\": \"...\", \"confidence\": 0.0-1.0}")

        return sb.toString()
    }

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
}
