package com.focal.ui.components

fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffMs = nowMs - timestampMs
    val diffMinutes = diffMs / 60_000
    val diffHours = diffMs / 3_600_000

    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "${diffMinutes} min ago"
        diffHours < 24 -> "${diffHours}h ago"
        else -> "yesterday"
    }
}
