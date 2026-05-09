package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.TransactionRepository
import com.focal.data.repository.WidgetRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString

private const val TAG = "WidgetCompute"

class WidgetComputeEngine(
    private val widgetRepository: WidgetRepository,
    private val transactionRepository: TransactionRepository
) {

    suspend fun computeAll() {
        val configs = widgetRepository.getAllConfigs()
        if (configs.isEmpty()) return

        val states = configs.map { config -> computeWidget(config) }
        widgetRepository.saveWidgetStates(states)
        Log.i(TAG, "Computed ${states.size} widget states")
    }

    private suspend fun computeWidget(config: WidgetConfigEntity): WidgetStateEntity {
        if (config.category == "finance") {
            return computeFinanceFromTransactions(config)
        }

        val filterApps = config.filterApps?.let {
            try { Json.decodeFromString<List<String>>(it) } catch (_: Exception) { null }
        }
        val rawData = if (filterApps != null) {
            widgetRepository.getExtractedData(config.category, filterApps)
        } else {
            widgetRepository.getExtractedData(config.category)
        }

        val data = rawData.filter { isValidRow(it, config.category) }

        if (data.isEmpty()) {
            return WidgetStateEntity(
                widgetId = config.id,
                headline = emptyHeadline(config),
                itemCount = 0
            )
        }

        val sourceApps = data.map { it.appPackage }.distinct()

        return when (config.operation) {
            "SUM" -> computeSum(config, data, sourceApps)
            "COUNT" -> computeCount(config, data, sourceApps)
            "LATEST" -> computeLatest(config, data, sourceApps)
            "LIST" -> computeList(config, data, sourceApps)
            "MAX" -> computeMax(config, data, sourceApps)
            "STATUS" -> computeStatus(config, data, sourceApps)
            else -> WidgetStateEntity(widgetId = config.id, headline = "Unknown operation")
        }
    }

    private suspend fun computeFinanceFromTransactions(config: WidgetConfigEntity): WidgetStateEntity {
        val transactions = transactionRepository.getAll()
        if (transactions.isEmpty()) {
            return WidgetStateEntity(
                widgetId = config.id,
                headline = "₹0",
                itemCount = 0
            )
        }

        val totalSent = transactions.filter { it.direction == "debit" }.sumOf { it.amount }
        val totalReceived = transactions.filter { it.direction == "credit" }.sumOf { it.amount }
        val net = totalReceived - totalSent
        val netPrefix = if (net >= 0) "+" else "-"
        val formatted = "${netPrefix}₹${"%.0f".format(kotlin.math.abs(net))}"

        val latest = transactions.maxByOrNull { it.postedAt }
        val latestPrefix = if (latest?.direction == "credit") "+" else "-"
        val latestMerchant = latest?.matchedMerchant?.takeIf { it.isNotBlank() }
            ?: latest?.rawMerchant?.takeIf { it.isNotBlank() }
            ?: "Unassigned"
        val badge = "${latestPrefix}₹${"%.0f".format(latest?.amount ?: 0.0)} $latestMerchant"

        val grouped = transactions.groupBy {
            it.matchedMerchant?.takeIf { m -> m.isNotBlank() }
                ?: it.rawMerchant?.takeIf { m -> m.isNotBlank() }
                ?: "Unassigned"
        }
        val detailLines = grouped.map { (key, txns) ->
            val sum = txns.sumOf { if (it.direction == "debit") -it.amount else it.amount }
            val prefix = if (sum >= 0) "+" else ""
            mapOf("label" to key, "value" to "${prefix}₹${"%.0f".format(kotlin.math.abs(sum))}")
        }.sortedByDescending { it["value"]?.removePrefix("+")?.removePrefix("-")?.removePrefix("₹")?.toDoubleOrNull() ?: 0.0 }

        val subtitle = "₹${"%.0f".format(totalSent)} sent · ₹${"%.0f".format(totalReceived)} received"

        val sourceApps = transactions.mapNotNull { it.matchedApp }.distinct()

        return WidgetStateEntity(
            widgetId = config.id,
            headline = formatted,
            subtitle = subtitle,
            badge = badge,
            detailJson = Json.encodeToString(detailLines),
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = transactions.size
        )
    }

    private fun emptyHeadline(config: WidgetConfigEntity): String {
        return when (config.operation) {
            "SUM" -> "₹0"
            "COUNT" -> "0"
            "STATUS" -> "No updates"
            else -> "No data yet"
        }
    }

    private fun computeSum(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val field = config.field ?: "amount"
        val items = data.mapNotNull { parseField(it.data, field)?.toDoubleOrNull() }
        val total = items.sum()
        val formatted = if (total == total.toLong().toDouble()) "₹${total.toLong()}" else "₹${"%.0f".format(total)}"

        val latest = data.maxByOrNull { it.extractedAt }
        val latestParsed = latest?.let { parseJson(it.data) }
        val badge = latestParsed?.let {
            val amt = parseField(it, field)
            val merchant = parseField(it, "merchant")
            if (amt != null && merchant != null) "+₹${amt} $merchant" else null
        }

        val grouped = data.groupBy { parseField(it.data, config.groupBy ?: "merchant") ?: "Other" }
        val detailLines = grouped.map { (key, items) ->
            val sum = items.mapNotNull { parseField(it.data, field)?.toDoubleOrNull() }.sum()
            mapOf("label" to key, "value" to "₹${"%.0f".format(sum)}")
        }

        val merchantCount = grouped.size
        return WidgetStateEntity(
            widgetId = config.id,
            headline = formatted,
            subtitle = if (merchantCount > 1) "Across $merchantCount merchants" else null,
            badge = badge,
            detailJson = Json.encodeToString(detailLines),
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeCount(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        return WidgetStateEntity(
            widgetId = config.id,
            headline = "${data.size}",
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeLatest(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val latest = data.maxByOrNull { it.extractedAt } ?: return WidgetStateEntity(widgetId = config.id)
        val parsed = parseJson(latest.data)
        val headline = parseField(parsed, "sender") ?: parseField(parsed, "item") ?: parseField(parsed, "entity") ?: "Latest"
        val snippet = parseField(parsed, "snippet") ?: parseField(parsed, "status") ?: ""

        return WidgetStateEntity(
            widgetId = config.id,
            headline = headline,
            subtitle = snippet,
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeList(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        // Try groupBy candidates in order until one produces non-Unknown groups
        val candidates = listOfNotNull(config.groupBy, "sender", "item", "merchant", "entity")
        val groupField = candidates.firstOrNull { field ->
            data.any { row -> parseField(row.data, field)?.let { it.length > 1 } == true }
        } ?: "sender"
        val grouped = data.groupBy { parseField(it.data, groupField) ?: "Unknown" }
        val topSender = grouped.maxByOrNull { it.value.size }
        val detailLines = grouped.map { (key, items) ->
            mapOf("label" to key, "value" to "${items.size}")
        }

        return WidgetStateEntity(
            widgetId = config.id,
            headline = "${topSender?.key ?: ""} · ${data.size}",
            detailJson = Json.encodeToString(detailLines),
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeMax(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val field = config.field ?: "amount"
        val max = data.maxByOrNull { parseField(it.data, field)?.toDoubleOrNull() ?: 0.0 }
        val parsed = max?.let { parseJson(it.data) }
        val value = parsed?.let { parseField(it, field) } ?: "0"
        val merchant = parsed?.let { parseField(it, "merchant") } ?: ""

        return WidgetStateEntity(
            widgetId = config.id,
            headline = "₹$value",
            badge = "$merchant",
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeStatus(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val byStatus = data.groupBy { parseField(it.data, "status") ?: "unknown" }
        val headline = byStatus.entries
            .sortedByDescending { it.value.size }
            .joinToString(", ") { "${it.value.size} ${it.key.replace('_', ' ')}" }

        val detailLines = data.map { row ->
            val parsed = parseJson(row.data)
            mapOf(
                "label" to (parseField(parsed, "item") ?: parseField(parsed, "merchant") ?: "Item"),
                "value" to (parseField(parsed, "status")?.replace('_', ' ') ?: "unknown")
            )
        }

        val latestRow = data.maxByOrNull { it.extractedAt }
        val latestMerchant = latestRow?.let { parseField(it.data, "merchant") }
        val latestStatus = latestRow?.let { parseField(it.data, "status")?.replace('_', ' ') }
        val badge = if (latestMerchant != null && latestStatus != null) "$latestMerchant · $latestStatus" else null

        return WidgetStateEntity(
            widgetId = config.id,
            headline = headline,
            badge = badge,
            detailJson = Json.encodeToString(detailLines),
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun isValidRow(row: ExtractedDataEntity, category: String): Boolean {
        val field = when (category) {
            "work" -> "entity"
            "personal" -> "sender"
            "logistics" -> "item"
            else -> return true
        }
        val value = parseField(row.data, field) ?: return false
        return value.length > 1 && value.any { it.isLetterOrDigit() }
    }

    private fun parseJson(json: String): JsonObject? {
        return try { Json.parseToJsonElement(json).jsonObject } catch (_: Exception) { null }
    }

    private fun parseField(json: String, field: String): String? = parseField(parseJson(json), field)

    private fun parseField(obj: JsonObject?, field: String): String? {
        if (obj == null) return null
        val element = obj[field] ?: return null
        return try { element.jsonPrimitive.content } catch (_: Exception) { null }
    }
}
