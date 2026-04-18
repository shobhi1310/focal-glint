package com.focal.intelligence

import android.util.Log
import org.json.JSONObject

object LlmResponseParser {

    fun parseClassification(raw: String): ClassificationResult? {
        return try {
            // Try JSON first
            val jsonStr = extractJson(raw)
            if (jsonStr != null) {
                val json = JSONObject(jsonStr)
                val rawCategory = json.optString("category", "").lowercase().trim()
                val category = normalizeCategory(rawCategory)
                if (category != null) {
                    val reason = if (json.has("reason")) json.getString("reason") else null
                    val confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f)
                    return ClassificationResult(
                        category = category,
                        classifiedBy = "llm",
                        confidence = confidence,
                        reason = reason
                    )
                }
            }

            // Fallback: parse markdown/text format (e.g. "**Category:** noise")
            parseMarkdownFormat(raw)
        } catch (e: Exception) {
            Log.w("LlmResponseParser", "Failed to parse LLM response: ${raw.take(200)}", e)
            null
        }
    }

    private val VALID_CATEGORIES = listOf("urgent", "actionable", "digest", "noise")

    /**
     * Normalize fuzzy LLM category responses to valid categories.
     * Returns null if the category cannot be mapped.
     */
    internal fun normalizeCategory(raw: String): String? {
        if (raw in VALID_CATEGORIES) return raw
        return when {
            raw == "action" || raw == "act" -> "actionable"
            raw == "info" || raw == "informational" || raw == "information" -> "digest"
            raw == "urg" -> "urgent"
            else -> null
        }
    }

    private fun parseMarkdownFormat(raw: String): ClassificationResult? {
        val text = raw.lowercase()

        val categoryPattern = Regex("\\*?\\*?category\\*?\\*?:?\\s*\\*?\\*?\\s*(\\w+)")
        val rawCategory = categoryPattern.find(text)?.groupValues?.get(1) ?: return null
        val category = normalizeCategory(rawCategory) ?: return null

        val reasonPattern = Regex("\\*?\\*?reason\\*?\\*?:?\\s*\\*?\\*?\\s*(.+)")
        val reason = reasonPattern.find(text)?.groupValues?.get(1)?.trim()

        val confidencePattern = Regex("\\*?\\*?confidence\\*?\\*?:?\\s*\\*?\\*?\\s*([0-9.]+)")
        val confidence = confidencePattern.find(text)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f

        return ClassificationResult(
            category = category,
            classifiedBy = "llm",
            confidence = confidence,
            reason = reason
        )
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

        try {
            val codeBlockPattern = Regex("`{3}(?:json)?\\s*\\n?(\\{[^}]+})", RegexOption.DOT_MATCHES_ALL)
            codeBlockPattern.find(trimmed)?.let { return it.groupValues[1] }
        } catch (_: Exception) { /* ICU regex fallback */ }

        val jsonPattern = Regex("\\{[^}]*\"category\"[^}]*}")
        jsonPattern.find(trimmed)?.let { return it.value }

        return null
    }
}
