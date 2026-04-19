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

    fun parseMattersClassification(raw: String): ClassificationResult? {
        return try {
            val jsonStr = extractJson(raw)
            if (jsonStr != null) {
                val json = JSONObject(jsonStr)
                val matters = json.optBoolean("matters", false)
                val reason = if (json.has("reason")) json.getString("reason") else null
                return ClassificationResult(
                    category = if (matters) ClassificationResult.MATTERS else ClassificationResult.NOISE,
                    classifiedBy = "llm",
                    confidence = 0.8f,
                    reason = reason
                )
            }

            // Fallback: look for "matters" or "noise" keywords
            val text = raw.lowercase()
            val category = when {
                text.contains("\"matters\": true") || text.contains("\"matters\":true") -> ClassificationResult.MATTERS
                text.contains("\"matters\": false") || text.contains("\"matters\":false") -> ClassificationResult.NOISE
                text.contains("matters") && !text.contains("doesn't matter") && !text.contains("does not matter") -> ClassificationResult.MATTERS
                text.contains("noise") || text.contains("promotional") || text.contains("doesn't matter") -> ClassificationResult.NOISE
                else -> return null
            }

            ClassificationResult(category = category, classifiedBy = "llm", confidence = 0.6f)
        } catch (e: Exception) {
            Log.w("LlmResponseParser", "Failed to parse matters response: ${raw.take(200)}", e)
            null
        }
    }

    fun parseNarrative(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        // Remove any preamble like "Here's a summary:" or "Summary:"
        val lower = trimmed.lowercase()
        val prefixes = listOf("here's a summary:", "here is a summary:", "summary:", "here's the summary:")
        val matchedPrefix = prefixes.firstOrNull { lower.startsWith(it) }
        val cleaned = if (matchedPrefix != null) trimmed.drop(matchedPrefix.length).trim() else trimmed
        if (cleaned.isBlank()) return null
        // Take just the first sentence/paragraph
        return cleaned.lines().firstOrNull { it.isNotBlank() }?.trim()
    }

    data class TopicContent(
        val title: String,
        val summary: String,
        val actions: List<SuggestedAction> = emptyList()
    )

    fun parseTopicContent(raw: String): TopicContent? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val titleMatch = Regex("(?:TITLE:\\s*)(.+)", RegexOption.IGNORE_CASE).find(trimmed)
        val summaryMatch = Regex("(?:SUMMARY:\\s*)(.+)", RegexOption.IGNORE_CASE).find(trimmed)

        val title: String
        val summary: String

        if (titleMatch != null && summaryMatch != null) {
            title = titleMatch.groupValues[1].trim().take(60)
            summary = summaryMatch.groupValues[1].trim().take(300)
        } else {
            val lines = trimmed.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return null
            title = lines.first().removePrefix("TITLE:").trim().take(60)
            summary = if (lines.size > 1) {
                lines.drop(1).first().removePrefix("SUMMARY:").trim().take(300)
            } else title
        }

        // Parse ACTIONS section
        val actionsStart = trimmed.indexOf("ACTIONS:", ignoreCase = true)
        val actions = if (actionsStart >= 0) {
            val actionsBlock = trimmed.substring(actionsStart + "ACTIONS:".length)
            actionsBlock.lines()
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .take(3)
                .mapNotNull { line ->
                    val parts = line.removePrefix("- ").split("|").map { it.trim() }
                    if (parts.size >= 3) {
                        val type = parts[1].lowercase().let {
                            if (it in SuggestedAction.VALID_TYPES) it else "open_app"
                        }
                        SuggestedAction(label = parts[0], type = type, app = parts[2])
                    } else null
                }
        } else emptyList()

        return TopicContent(title = title, summary = summary, actions = actions)
    }

    private val VALID_CATEGORIES = listOf("matters", "noise")

    /**
     * Normalize fuzzy LLM category responses to valid categories.
     * Returns null if the category cannot be mapped.
     */
    internal fun normalizeCategory(raw: String): String? {
        if (raw in VALID_CATEGORIES) return raw
        return when {
            raw == "promo" || raw == "promotional" || raw == "spam" -> "noise"
            raw == "matter" || raw == "important" || raw == "personal" -> "matters"
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

        val jsonPattern = Regex("\\{[^}]*\"(?:category|matters)\"[^}]*}")
        jsonPattern.find(trimmed)?.let { return it.value }

        return null
    }
}
