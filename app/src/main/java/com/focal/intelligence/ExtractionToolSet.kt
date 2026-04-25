package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "ExtractionTool"

@Serializable
data class FinanceData(val amount: Double, val merchant: String, val category: String, val direction: String)

@Serializable
data class WorkData(val entity: String, val sender: String, val action: String, val repo: String? = null)

@Serializable
data class PersonalData(val sender: String, val channel: String, val count: Int, val snippet: String? = null)

@Serializable
data class LogisticsData(val item: String, val merchant: String, val status: String, val etaMinutes: Int? = null)

data class ExtractionResult(val notificationIndex: Int, val category: String, val dataJson: String)

class ExtractFinanceTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract financial transaction data from a notification about money, payments, or banking")
    fun extractFinance(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Transaction amount as a number") amount: Double,
        @ToolParam("Merchant or payee name") merchant: String,
        @ToolParam("Spending category: food, transport, shopping, bills, transfer, or other") category: String,
        @ToolParam("Transaction direction: debit or credit") direction: String
    ): Map<String, Any> {
        Log.i(TAG, "finance: index=$index amount=$amount merchant=$merchant")
        _results.add(ExtractionResult(index, "finance", Json.encodeToString(FinanceData(amount, merchant, category, direction))))
        return mapOf("status" to "ok")
    }
}

class ExtractWorkTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract work item data from a notification about PRs, issues, code reviews, or work tasks")
    fun extractWork(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Work entity identifier like PR #482, Issue FOCAL-31, or thread name") entity: String,
        @ToolParam("Person who sent or triggered this") sender: String,
        @ToolParam("Action type: review_requested, merged, commented, assigned, mentioned, or other") action: String,
        @ToolParam("Repository or project name if mentioned") repo: String
    ): Map<String, Any> {
        Log.i(TAG, "work: index=$index entity=$entity sender=$sender action=$action")
        _results.add(ExtractionResult(index, "work", Json.encodeToString(WorkData(entity, sender, action, repo.takeIf { it.isNotBlank() }))))
        return mapOf("status" to "ok")
    }
}

class ExtractPersonalTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract personal contact data from a notification about calls, messages, or personal communication")
    fun extractPersonal(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Name of the person who reached out") sender: String,
        @ToolParam("Communication channel: call, message, email, or other") channel: String,
        @ToolParam("Number of attempts or messages, default 1") count: Int,
        @ToolParam("Brief content snippet if available") snippet: String
    ): Map<String, Any> {
        Log.i(TAG, "personal: index=$index sender=$sender channel=$channel count=$count")
        _results.add(ExtractionResult(index, "personal", Json.encodeToString(PersonalData(sender, channel, count, snippet.takeIf { it.isNotBlank() }))))
        return mapOf("status" to "ok")
    }
}

class ExtractLogisticsTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract delivery or logistics data from a notification about orders, shipments, or tracking")
    fun extractLogistics(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Item or order description") item: String,
        @ToolParam("Merchant or delivery service name") merchant: String,
        @ToolParam("Delivery status: ordered, shipped, out_for_delivery, delivered, or cancelled") status: String,
        @ToolParam("Estimated arrival time in minutes, or -1 if unknown") etaMinutes: Int
    ): Map<String, Any> {
        Log.i(TAG, "logistics: index=$index item=$item merchant=$merchant status=$status")
        _results.add(ExtractionResult(index, "logistics", Json.encodeToString(LogisticsData(item, merchant, status, etaMinutes.takeIf { it >= 0 }))))
        return mapOf("status" to "ok")
    }
}

object ExtractionToolFactory {
    fun createTools(activeCategories: List<String>): Map<String, ToolSet> {
        val tools = mutableMapOf<String, ToolSet>()
        if ("finance" in activeCategories) tools["finance"] = ExtractFinanceTool()
        if ("work" in activeCategories) tools["work"] = ExtractWorkTool()
        if ("personal" in activeCategories) tools["personal"] = ExtractPersonalTool()
        if ("logistics" in activeCategories) tools["logistics"] = ExtractLogisticsTool()
        return tools
    }

    fun collectResults(tools: Map<String, ToolSet>): List<ExtractionResult> {
        return tools.values.flatMap { tool ->
            when (tool) {
                is ExtractFinanceTool -> tool.results
                is ExtractWorkTool -> tool.results
                is ExtractPersonalTool -> tool.results
                is ExtractLogisticsTool -> tool.results
                else -> emptyList()
            }
        }
    }
}
