package com.focal.intelligence

data class ClassificationResult(
    val category: String,
    val classifiedBy: String,
    val ruleId: String? = null,
    val confidence: Float = 1.0f,
    val reason: String? = null
) {
    companion object {
        const val MATTERS = "matters"
        const val NOISE = "noise"
        const val UNCATEGORIZED = "uncategorized"
    }
}
