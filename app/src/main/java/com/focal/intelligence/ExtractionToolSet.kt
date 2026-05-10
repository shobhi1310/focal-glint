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

@Serializable
data class BankTransactionData(
    val amount: Double,
    val direction: String,
    val account: String,
    val bank: String,
    val merchant: String
)

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

    @Tool("Call only for software or project workflow events that reference a concrete trackable item — a pull request, code review, issue, or automated build result with an ID or name. The notification must be about an action on that item (opened, merged, assigned, failed, review requested). Skip professional social network activity, chat messages between colleagues, meeting invites, and anything without a specific item reference to act on.")
    fun extractWork(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("The specific item being acted on: PR number, issue ID, build name, or ticket reference") entity: String,
        @ToolParam("Person who triggered the event") sender: String,
        @ToolParam("Action type: review_requested, merged, commented, assigned, build_failed, build_passed, or mentioned") action: String,
        @ToolParam("Repository or project name if present, otherwise empty") repo: String
    ): Map<String, Any> {
        Log.i(TAG, "work: index=$index entity=$entity sender=$sender action=$action")
        _results.add(ExtractionResult(index, "work", Json.encodeToString(WorkData(entity, sender, action, repo.takeIf { it.isNotBlank() }))))
        return mapOf("status" to "ok")
    }
}

class ExtractPersonalTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Call only when a real named person directly sent the user a 1-on-1 message or placed a call — the communication is intentionally addressed to this specific user. Skip: broadcast or channel posts sent to many people at once, meeting reminders and calendar alerts, automated system messages, social activity notifications (reactions, follows, story posts, likes), professional network alerts, and any notification where an app or service is the sender rather than a real individual.")
    fun extractPersonal(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Full name of the real person who sent the message or call — never a generic label like Citizen, User, Customer, Member, You, App, or System; if no real human name is present do not call this tool") sender: String,
        @ToolParam("How they reached out: call, message, email, or dm") channel: String,
        @ToolParam("Number of messages or call attempts, default 1") count: Int,
        @ToolParam("Brief snippet of what they said, if visible") snippet: String
    ): Map<String, Any> {
        Log.i(TAG, "personal: index=$index sender=$sender channel=$channel count=$count")
        _results.add(ExtractionResult(index, "personal", Json.encodeToString(PersonalData(sender, channel, count, snippet.takeIf { it.isNotBlank() }))))
        return mapOf("status" to "ok")
    }
}

class ExtractLogisticsTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Call only when an order the user placed is actively moving through delivery — the notification reports a real status change: shipped, out for delivery, delivered, delayed, or delivery failed. Skip promotional offers, cart or wishlist reminders, deals and discounts, food or restaurant discovery, and any notification not about a specific order currently in transit.")
    fun extractLogistics(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("What is being delivered — item name or order description") item: String,
        @ToolParam("The seller or delivery service handling the shipment") merchant: String,
        @ToolParam("Current delivery status: shipped, out_for_delivery, delivered, delayed, or cancelled") status: String,
        @ToolParam("Estimated minutes until arrival, or -1 if not stated") etaMinutes: Int
    ): Map<String, Any> {
        Log.i(TAG, "logistics: index=$index item=$item merchant=$merchant status=$status")
        _results.add(ExtractionResult(index, "logistics", Json.encodeToString(LogisticsData(item, merchant, status, etaMinutes.takeIf { it >= 0 }))))
        return mapOf("status" to "ok")
    }
}

class ExtractBankTransactionTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract transaction details from a bank SMS about money debited or credited")
    fun extractBankTransaction(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Transaction amount as a number") amount: Double,
        @ToolParam("Transaction direction: debit or credit") direction: String,
        @ToolParam("Masked bank account number e.g. *3371 or XX023") account: String,
        @ToolParam("Bank name e.g. HDFC Bank, ICICI Bank") bank: String,
        @ToolParam("Merchant or payee name from SMS, empty if not present") merchant: String
    ): Map<String, Any> {
        Log.i(TAG, "bankTransaction: index=$index amount=$amount direction=$direction account=$account bank=$bank")
        val data = BankTransactionData(amount, direction, account, bank, merchant)
        _results.add(ExtractionResult(index, "bank_transaction", Json.encodeToString(data)))
        return mapOf("status" to "ok")
    }
}

class NoExtractionTool : ToolSet {
    private val _calledIndices = mutableSetOf<Int>()
    val calledIndices: Set<Int> get() = _calledIndices

    @Tool("Call for every 'matters' notification when none of the other extraction tools apply to it. Do not call for 'noise' notifications.")
    fun noExtraction(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Short snake_case reason why no extraction category applies, e.g. none_matched, general_update, reminder, not_transactional") reason: String
    ): Map<String, Any> {
        Log.i(TAG, "noExtraction: index=$index reason=$reason")
        _calledIndices.add(index)
        return mapOf("status" to "ok")
    }

    companion object { private const val TAG = "NoExtractionTool" }
}

object ExtractionToolFactory {
    fun createTools(activeCategories: List<String>): Map<String, ToolSet> {
        val tools = mutableMapOf<String, ToolSet>()
        if ("finance" in activeCategories) tools["finance"] = ExtractFinanceTool()
        if ("work" in activeCategories) tools["work"] = ExtractWorkTool()
        if ("personal" in activeCategories) tools["personal"] = ExtractPersonalTool()
        if ("logistics" in activeCategories) tools["logistics"] = ExtractLogisticsTool()
        if ("bank_transaction" in activeCategories) tools["bank_transaction"] = ExtractBankTransactionTool()
        // Always included when any extraction category is active
        if (tools.isNotEmpty()) tools["none"] = NoExtractionTool()
        return tools
    }

    fun collectResults(tools: Map<String, ToolSet>): List<ExtractionResult> {
        return tools.values.flatMap { tool ->
            when (tool) {
                is ExtractFinanceTool -> tool.results
                is ExtractWorkTool -> tool.results
                is ExtractPersonalTool -> tool.results
                is ExtractLogisticsTool -> tool.results
                is ExtractBankTransactionTool -> tool.results
                else -> emptyList()
            }
        }
    }
}
