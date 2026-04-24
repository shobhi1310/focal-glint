package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import org.json.JSONArray

data class EmbeddingRequest(
    val title: String,
    val text: String,
    val combinedText: String
)

object EmbeddingTextFormatter {

    fun buildRequest(notification: NotificationEntity): EmbeddingRequest? {
        val channelId = notification.notificationKey?.split("|")?.getOrNull(2)
            ?.takeIf { it.isNotBlank() && it != "null" }
        val appPrefix = if (channelId != null) {
            "${notification.appName}/$channelId"
        } else {
            notification.appName
        }
        val body = when {
            notification.extrasJson != null -> extractRecentThreadText(notification.extrasJson)
            notification.bigText != null -> buildEmailBody(notification.content, notification.bigText)
            else -> notification.content.takeIf { it.isNotBlank() }
        } ?: return null

        val cleanTitle = if (notification.title.isBlank()) {
            ""
        } else {
            normalize("${appPrefix} — ${notification.title}")
        }
        val cleanBody = normalize(body)
        if (cleanBody.isBlank()) return null

        return EmbeddingRequest(
            title = cleanTitle,
            text = cleanBody,
            combinedText = if (cleanTitle.isNotBlank()) "$cleanTitle: $cleanBody" else cleanBody
        )
    }

    fun format(request: EmbeddingRequest): String {
        return "task: clustering | query: ${request.combinedText}"
    }

    private fun extractRecentThreadText(extrasJson: String?): String? {
        if (extrasJson == null) return null
        return try {
            val arr = JSONArray(extrasJson)
            val texts = (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
                .distinct()
                .takeLast(3)
            if (texts.isEmpty()) null else texts.joinToString(" | ")
        } catch (_: Exception) {
            null
        }
    }

    private fun buildEmailBody(content: String, bigText: String): String {
        val clean = normalize(bigText)
        return if (clean.startsWith(content.take(50))) clean else "$content\n$clean"
    }

    private fun normalize(value: String): String {
        return value
            .replace(Regex("\\p{Cf}"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
