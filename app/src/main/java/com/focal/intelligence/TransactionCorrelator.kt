package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.TransactionEntity
import com.focal.data.repository.TransactionRepository
import com.focal.data.repository.WidgetRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

private const val TAG = "TransactionCorrelator"

class TransactionCorrelator(
    private val transactionRepository: TransactionRepository,
    private val widgetRepository: WidgetRepository
) {
    suspend fun correlate() {
        val unmatched = transactionRepository.getUnmatched()
        if (unmatched.isEmpty()) {
            Log.d(TAG, "No unmatched transactions")
            return
        }

        val candidates = widgetRepository.getUnmatchedFinanceExtractions()
        Log.d(TAG, "Correlating: ${unmatched.size} transactions, ${candidates.size} candidates")

        val usedCandidateIds = mutableSetOf<Long>()

        for (txn in unmatched) {
            val match = findMatch(txn, candidates, usedCandidateIds)
            if (match != null) {
                val parsed = parseFinanceData(match.data)
                transactionRepository.updateMatch(
                    transactionId = txn.id,
                    matchedNotificationId = match.notificationId,
                    matchedApp = match.appPackage,
                    matchedMerchant = parsed?.merchant ?: "",
                    category = parsed?.category
                )
                transactionRepository.markExtractionMatched(match.id, txn.id)
                usedCandidateIds.add(match.id)
                Log.d(TAG, "Matched txn ${txn.id} (₹${txn.amount} ${txn.direction}) → ${match.appPackage} (${parsed?.merchant})")
            } else {
                Log.d(TAG, "Unassigned txn ${txn.id} (₹${txn.amount} ${txn.direction} via ${txn.bank} ${txn.account})")
            }
        }
    }

    private fun findMatch(
        txn: TransactionEntity,
        candidates: List<ExtractedDataEntity>,
        usedIds: Set<Long>
    ): ExtractedDataEntity? {
        val amountMatches = candidates.filter { candidate ->
            if (candidate.id in usedIds) return@filter false
            val parsed = parseFinanceData(candidate.data) ?: return@filter false
            amountsMatch(txn.amount, parsed.amount) && directionsMatch(txn.direction, parsed.direction)
        }

        return when (amountMatches.size) {
            0 -> null
            1 -> amountMatches.first()
            else -> amountMatches.minByOrNull { abs(it.extractedAt - txn.postedAt) }
        }
    }

    private fun amountsMatch(a: Double, b: Double): Boolean {
        return abs(a - b) < 0.01
    }

    private fun directionsMatch(bankDir: String, appDir: String): Boolean {
        return bankDir.lowercase().trim() == appDir.lowercase().trim()
    }

    private data class ParsedFinance(
        val amount: Double,
        val merchant: String,
        val category: String?,
        val direction: String
    )

    private fun parseFinanceData(json: String): ParsedFinance? {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            ParsedFinance(
                amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: return null,
                merchant = obj["merchant"]?.jsonPrimitive?.content ?: "",
                category = obj["category"]?.jsonPrimitive?.content,
                direction = obj["direction"]?.jsonPrimitive?.content ?: "debit"
            )
        } catch (_: Exception) { null }
    }
}
