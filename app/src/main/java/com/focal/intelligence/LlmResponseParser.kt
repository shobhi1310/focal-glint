package com.focal.intelligence

import android.util.Log
import org.json.JSONObject

object LlmResponseParser {

    fun parseClassification(raw: String): ClassificationResult? {
        return try {
            val jsonStr = extractJson(raw) ?: return null
            val json = JSONObject(jsonStr)

            val category = json.optString("category", "").lowercase().trim()
            if (category !in listOf("urgent", "informational", "noise")) return null

            val reason = if (json.has("reason")) json.getString("reason") else null
            val confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f)

            ClassificationResult(
                category = category,
                classifiedBy = "llm",
                confidence = confidence,
                reason = reason
            )
        } catch (e: Exception) {
            Log.w("LlmResponseParser", "Failed to parse LLM response: ${raw.take(200)}", e)
            null
        }
    }

    fun parseSummary(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return trimmed.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("Summary:") }
            ?.trim()
            ?: trimmed.lines().lastOrNull()?.trim()
    }

    private fun extractJson(raw: String): String? {
        val trimmed = raw.trim()

        if (trimmed.startsWith("{")) {
            val endIndex = trimmed.indexOf('}')
            if (endIndex > 0) return trimmed.substring(0, endIndex + 1)
        }

        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\{[^}]+})", RegexOption.DOT_MATCHES_ALL)
        codeBlockPattern.find(trimmed)?.let { return it.groupValues[1] }

        val jsonPattern = Regex("\\{[^}]*\"category\"[^}]*}")
        jsonPattern.find(trimmed)?.let { return it.value }

        return null
    }
}
