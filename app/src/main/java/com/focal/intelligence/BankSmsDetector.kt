package com.focal.intelligence

object BankSmsDetector {

    private val BANK_SENDER_PATTERN = Regex("^[A-Z]{2}-[A-Za-z]{3,}")

    private val TRANSACTION_KEYWORDS = listOf(
        "debited", "credited", "sent rs", "received rs",
        "rs.", "inr ", "withdrawn", "transferred",
        "mandate", "emi deducted", "deducted"
    )

    private val EXCLUSION_KEYWORDS = listOf(
        "otp", "one time password", "verification",
        "verification code", "login"
    )

    private const val MESSAGING_PACKAGE = "com.google.android.apps.messaging"

    fun isBankTransaction(packageName: String, title: String, content: String): Boolean {
        if (packageName != MESSAGING_PACKAGE) return false

        val titleTrimmed = title.trim().removePrefix("⁨").removePrefix("⁩")
        if (!BANK_SENDER_PATTERN.containsMatchIn(titleTrimmed)) return false

        val contentLower = content.lowercase()
        val hasTransactionKeyword = TRANSACTION_KEYWORDS.any { contentLower.contains(it) }
        val hasExclusionKeyword = EXCLUSION_KEYWORDS.any { contentLower.contains(it) }

        return hasTransactionKeyword && !hasExclusionKeyword
    }
}
