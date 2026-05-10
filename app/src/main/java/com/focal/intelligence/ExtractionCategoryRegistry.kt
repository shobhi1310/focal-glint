package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.google.ai.edge.litertlm.ToolSet

internal object ExtractionCategoryRegistry {
    const val NONE = "none"
    const val BANK_TRANSACTION = "bank_transaction"

    private val displayOrder = listOf("finance", "work", "personal", "logistics", BANK_TRANSACTION)

    fun visibleCategories(categories: Set<String>): List<String> {
        val visible = categories - NONE
        return displayOrder.filter { it in visible } + visible.filter { it !in displayOrder }.sorted()
    }

    fun bankIndices(notifications: List<NotificationEntity>): List<Int> =
        notifications.mapIndexedNotNull { i, notification ->
            if (notification.isBankTransaction) i + 1 else null
        }
}

internal data class ExtractionToolPlan(
    val bankIndices: List<Int>,
    val effectiveExtractionTools: Map<String, ToolSet>,
    val conversationTools: List<ToolSet>,
    val visibleCategories: List<String>
)

internal object ExtractionToolPlanner {
    fun plan(
        classifyTool: BatchClassifyNotificationTool,
        extractionTools: Map<String, ToolSet>,
        notifications: List<NotificationEntity>
    ): ExtractionToolPlan {
        val bankIndices = ExtractionCategoryRegistry.bankIndices(notifications)
        val injectedBankTools =
            if (bankIndices.isNotEmpty() && ExtractionCategoryRegistry.BANK_TRANSACTION !in extractionTools) {
                ExtractionToolFactory.createTools(listOf(ExtractionCategoryRegistry.BANK_TRANSACTION))
                    .filterKeys { it != ExtractionCategoryRegistry.NONE }
            } else {
                emptyMap()
            }

        val effectiveExtractionTools = extractionTools + injectedBankTools
        val conversationTools = listOf(classifyTool) + extractionTools.values + injectedBankTools.values
        val visibleCategories = ExtractionCategoryRegistry.visibleCategories(effectiveExtractionTools.keys)

        return ExtractionToolPlan(
            bankIndices = bankIndices,
            effectiveExtractionTools = effectiveExtractionTools,
            conversationTools = conversationTools,
            visibleCategories = visibleCategories
        )
    }
}
