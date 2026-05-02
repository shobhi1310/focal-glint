# Bank SMS Source of Truth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use bank debit/credit SMS as the authoritative record of money movement in the Finance widget, with pure-code correlation to app notifications for merchant attribution.

**Architecture:** Deterministic gate flags bank transaction SMS at capture time. Mandatory `extractBankTransaction` tool fires in the same LLM pass as classification (no extra KV cache contention). Pure-code correlator matches transactions to app extractions by amount + direction. Finance widget reads from `transactions` table instead of `extracted_data`.

**Tech Stack:** Kotlin, Room (SQLite), Jetpack Compose, LiteRT-LM / Cloud LLM, Hilt DI

---

## File Structure

### New files:
- `app/src/main/java/com/focal/data/db/entity/TransactionEntity.kt` — Room entity for bank-verified transactions
- `app/src/main/java/com/focal/data/db/dao/TransactionDao.kt` — Room DAO for transactions
- `app/src/main/java/com/focal/data/repository/TransactionRepository.kt` — Transaction CRUD
- `app/src/main/java/com/focal/intelligence/BankSmsDetector.kt` — Deterministic gate for bank SMS detection
- `app/src/main/java/com/focal/intelligence/TransactionCorrelator.kt` — Amount+direction matching engine

### Modified files:
- `app/src/main/java/com/focal/data/db/entity/NotificationEntity.kt` — Add `is_bank_transaction` column
- `app/src/main/java/com/focal/data/db/entity/ExtractedDataEntity.kt` — Add `matched_transaction_id` column
- `app/src/main/java/com/focal/data/db/dao/ExtractedDataDao.kt` — Add unmatched query
- `app/src/main/java/com/focal/data/db/FocalDatabase.kt` — Add TransactionDao, migration v8→v9
- `app/src/main/java/com/focal/data/notification/FocalNotificationListener.kt` — Call BankSmsDetector
- `app/src/main/java/com/focal/intelligence/ExtractionToolSet.kt` — Add BankTransactionData + ExtractBankTransactionTool
- `app/src/main/java/com/focal/intelligence/CloudClassifier.kt` — Add extractBankTransaction tool definition
- `app/src/main/java/com/focal/intelligence/PromptBuilder.kt` — Add mandatory extraction instruction
- `app/src/main/java/com/focal/intelligence/Classifier.kt` — Register bank transaction tool for flagged batches
- `app/src/main/java/com/focal/worker/InferenceWorker.kt` — Add correlation step
- `app/src/main/java/com/focal/intelligence/WidgetComputeEngine.kt` — Finance reads from transactions
- `app/src/main/java/com/focal/ui/pulse/PulseDetailViewModel.kt` — Load transactions for finance
- `app/src/main/java/com/focal/ui/pulse/PulseDetailScreen.kt` — Transaction row display
- `app/src/main/java/com/focal/di/IntelligenceModule.kt` — Wire new providers

---

### Task 1: Room Migration — NotificationEntity + TransactionEntity + ExtractedDataEntity

**Files:**
- Modify: `app/src/main/java/com/focal/data/db/entity/NotificationEntity.kt`
- Create: `app/src/main/java/com/focal/data/db/entity/TransactionEntity.kt`
- Modify: `app/src/main/java/com/focal/data/db/entity/ExtractedDataEntity.kt`
- Modify: `app/src/main/java/com/focal/data/db/FocalDatabase.kt`

- [ ] **Step 1: Add is_bank_transaction to NotificationEntity**

In `NotificationEntity.kt`, add before the `extractedCategories` field:

```kotlin
@ColumnInfo(name = "is_bank_transaction")
val isBankTransaction: Boolean = false,
```

- [ ] **Step 2: Create TransactionEntity**

Create `app/src/main/java/com/focal/data/db/entity/TransactionEntity.kt`:

```kotlin
package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "notification_id")
    val notificationId: String,

    val amount: Double,

    val direction: String,

    val account: String,

    val bank: String,

    @ColumnInfo(name = "raw_merchant")
    val rawMerchant: String? = null,

    @ColumnInfo(name = "matched_notification_id")
    val matchedNotificationId: String? = null,

    @ColumnInfo(name = "matched_app")
    val matchedApp: String? = null,

    @ColumnInfo(name = "matched_merchant")
    val matchedMerchant: String? = null,

    val category: String? = null,

    @ColumnInfo(name = "posted_at")
    val postedAt: Long,

    @ColumnInfo(name = "matched_at")
    val matchedAt: Long? = null
)
```

- [ ] **Step 3: Add matched_transaction_id to ExtractedDataEntity**

In `ExtractedDataEntity.kt`, add after the `extractedAt` field:

```kotlin
@ColumnInfo(name = "matched_transaction_id")
val matchedTransactionId: Long? = null
```

- [ ] **Step 4: Write migration v8→v9 and update FocalDatabase**

In `FocalDatabase.kt`, update version to 9, add TransactionEntity to entities list, add TransactionDao, and add migration:

```kotlin
@Database(
    entities = [
        NotificationEntity::class,
        RuleEntity::class,
        CorrectionEntity::class,
        AppProfileEntity::class,
        TopicEntity::class,
        WidgetConfigEntity::class,
        ExtractedDataEntity::class,
        WidgetStateEntity::class,
        TransactionEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class FocalDatabase : RoomDatabase() {
    // ... existing DAOs ...
    abstract fun transactionDao(): TransactionDao
```

Add the migration:

```kotlin
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notifications ADD COLUMN is_bank_transaction INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                notification_id TEXT NOT NULL,
                amount REAL NOT NULL,
                direction TEXT NOT NULL,
                account TEXT NOT NULL,
                bank TEXT NOT NULL,
                raw_merchant TEXT,
                matched_notification_id TEXT,
                matched_app TEXT,
                matched_merchant TEXT,
                category TEXT,
                posted_at INTEGER NOT NULL,
                matched_at INTEGER
            )
        """.trimIndent())
        db.execSQL("ALTER TABLE extracted_data ADD COLUMN matched_transaction_id INTEGER")
    }
}
```

Add `MIGRATION_8_9` to the migration list in the database builder inside `FocalNotificationListener.kt` and anywhere else `FocalDatabase` is built. The main builder is in `FocalNotificationListener.kt`:

```kotlin
.addMigrations(
    FocalDatabase.MIGRATION_1_2, FocalDatabase.MIGRATION_2_3,
    FocalDatabase.MIGRATION_3_4, FocalDatabase.MIGRATION_4_5,
    FocalDatabase.MIGRATION_5_6, FocalDatabase.MIGRATION_6_7,
    FocalDatabase.MIGRATION_7_8, FocalDatabase.MIGRATION_8_9
)
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/focal/data/db/entity/TransactionEntity.kt \
       app/src/main/java/com/focal/data/db/entity/NotificationEntity.kt \
       app/src/main/java/com/focal/data/db/entity/ExtractedDataEntity.kt \
       app/src/main/java/com/focal/data/db/FocalDatabase.kt \
       app/src/main/java/com/focal/data/notification/FocalNotificationListener.kt
git commit -m "feat: add TransactionEntity + migration v8→v9"
```

---

### Task 2: TransactionDao + TransactionRepository

**Files:**
- Create: `app/src/main/java/com/focal/data/db/dao/TransactionDao.kt`
- Create: `app/src/main/java/com/focal/data/repository/TransactionRepository.kt`
- Modify: `app/src/main/java/com/focal/data/db/dao/ExtractedDataDao.kt`

- [ ] **Step 1: Create TransactionDao**

Create `app/src/main/java/com/focal/data/db/dao/TransactionDao.kt`:

```kotlin
package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.focal.data.db.entity.TransactionEntity

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE matched_notification_id IS NULL")
    suspend fun getUnmatched(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY posted_at DESC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("""
        UPDATE transactions SET
            matched_notification_id = :matchedNotificationId,
            matched_app = :matchedApp,
            matched_merchant = :matchedMerchant,
            category = :category,
            matched_at = :matchedAt
        WHERE id = :id
    """)
    suspend fun updateMatch(
        id: Long,
        matchedNotificationId: String,
        matchedApp: String,
        matchedMerchant: String,
        category: String?,
        matchedAt: Long
    )

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT * FROM transactions WHERE notification_id = :notificationId LIMIT 1")
    suspend fun getByNotificationId(notificationId: String): TransactionEntity?
}
```

- [ ] **Step 2: Add getUnmatchedFinance to ExtractedDataDao**

In `ExtractedDataDao.kt`, add:

```kotlin
@Query("SELECT * FROM extracted_data WHERE category = 'finance' AND matched_transaction_id IS NULL")
suspend fun getUnmatchedFinance(): List<ExtractedDataEntity>

@Query("UPDATE extracted_data SET matched_transaction_id = :transactionId WHERE id = :id")
suspend fun setMatchedTransaction(id: Long, transactionId: Long)
```

- [ ] **Step 3: Create TransactionRepository**

Create `app/src/main/java/com/focal/data/repository/TransactionRepository.kt`:

```kotlin
package com.focal.data.repository

import com.focal.data.db.dao.ExtractedDataDao
import com.focal.data.db.dao.TransactionDao
import com.focal.data.db.entity.TransactionEntity

class TransactionRepository(
    private val transactionDao: TransactionDao,
    private val extractedDataDao: ExtractedDataDao
) {
    suspend fun insert(transaction: TransactionEntity): Long {
        return transactionDao.insert(transaction)
    }

    suspend fun getUnmatched(): List<TransactionEntity> {
        return transactionDao.getUnmatched()
    }

    suspend fun getAll(): List<TransactionEntity> {
        return transactionDao.getAll()
    }

    suspend fun updateMatch(
        transactionId: Long,
        matchedNotificationId: String,
        matchedApp: String,
        matchedMerchant: String,
        category: String?
    ) {
        transactionDao.updateMatch(
            id = transactionId,
            matchedNotificationId = matchedNotificationId,
            matchedApp = matchedApp,
            matchedMerchant = matchedMerchant,
            category = category,
            matchedAt = System.currentTimeMillis()
        )
    }

    suspend fun markExtractionMatched(extractionId: Long, transactionId: Long) {
        extractedDataDao.setMatchedTransaction(extractionId, transactionId)
    }

    suspend fun deleteById(id: Long) {
        transactionDao.deleteById(id)
    }

    suspend fun getByNotificationId(notificationId: String): TransactionEntity? {
        return transactionDao.getByNotificationId(notificationId)
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/data/db/dao/TransactionDao.kt \
       app/src/main/java/com/focal/data/db/dao/ExtractedDataDao.kt \
       app/src/main/java/com/focal/data/repository/TransactionRepository.kt
git commit -m "feat: add TransactionDao and TransactionRepository"
```

---

### Task 3: BankSmsDetector + FocalNotificationListener Integration

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/BankSmsDetector.kt`
- Modify: `app/src/main/java/com/focal/data/notification/FocalNotificationListener.kt`

- [ ] **Step 1: Create BankSmsDetector**

Create `app/src/main/java/com/focal/intelligence/BankSmsDetector.kt`:

```kotlin
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
```

- [ ] **Step 2: Update FocalNotificationListener to flag bank SMS**

In `FocalNotificationListener.kt`, after the line `val classified = if (ruleResult != null) {` block (around line 84-96), modify the entity creation to include the bank transaction flag. Replace the section from `val ruleResult = rulesEngine.classify(entity)` through `repository.upsertNotification(classified)`:

```kotlin
val isBankTxn = BankSmsDetector.isBankTransaction(
    entity.packageName, entity.title, entity.content
)

val ruleResult = rulesEngine.classify(entity)
val classified = if (ruleResult != null) {
    entity.copy(
        category = ruleResult.category,
        classifiedBy = ruleResult.classifiedBy,
        ruleId = ruleResult.ruleId,
        processedAt = System.currentTimeMillis(),
        isBankTransaction = isBankTxn
    )
} else {
    entity.copy(isBankTransaction = isBankTxn)
}

repository.upsertNotification(classified)
Log.d("FocalListener", "Saved: ${classified.title} -> ${classified.category} (${classified.classifiedBy}) bankTxn=$isBankTxn")
```

Add the import at the top:

```kotlin
import com.focal.intelligence.BankSmsDetector
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/BankSmsDetector.kt \
       app/src/main/java/com/focal/data/notification/FocalNotificationListener.kt
git commit -m "feat: add BankSmsDetector + flag bank SMS in listener"
```

---

### Task 4: ExtractBankTransaction Tool (On-Device + Cloud)

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/ExtractionToolSet.kt`
- Modify: `app/src/main/java/com/focal/intelligence/CloudClassifier.kt`

- [ ] **Step 1: Add BankTransactionData and ExtractBankTransactionTool to ExtractionToolSet.kt**

Add after the existing `LogisticsData` class:

```kotlin
@Serializable
data class BankTransactionData(
    val amount: Double,
    val direction: String,
    val account: String,
    val bank: String,
    val merchant: String
)
```

Add the tool class after `ExtractLogisticsTool`:

```kotlin
class ExtractBankTransactionTool : ToolSet {
    private val results = mutableListOf<ExtractionResult>()

    @Tool("Extract transaction details from a bank SMS about money debited or credited")
    fun extractBankTransaction(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Transaction amount as a number") amount: Double,
        @ToolParam("Transaction direction: debit or credit") direction: String,
        @ToolParam("Masked bank account number e.g. *3371 or XX023") account: String,
        @ToolParam("Bank name e.g. HDFC Bank, ICICI Bank") bank: String,
        @ToolParam("Merchant or payee name from SMS, empty if not present") merchant: String
    ): Map<String, Any> {
        val data = BankTransactionData(amount, direction, account, bank, merchant)
        results.add(ExtractionResult(
            notificationIndex = index,
            category = "bank_transaction",
            dataJson = Json.encodeToString(data)
        ))
        return mapOf("status" to "extracted", "index" to index)
    }

    fun getResults(): List<ExtractionResult> = results.toList()
}
```

Update `ExtractionToolFactory.createTools()` — add at the end of the method:

```kotlin
if ("bank_transaction" in categories) {
    tools["bank_transaction"] = ExtractBankTransactionTool()
}
```

Update `ExtractionToolFactory.collectResults()` — add to the results collection:

```kotlin
for ((category, toolSet) in tools) {
    when (toolSet) {
        is ExtractFinanceTool -> results.addAll(toolSet.getResults())
        is ExtractWorkTool -> results.addAll(toolSet.getResults())
        is ExtractPersonalTool -> results.addAll(toolSet.getResults())
        is ExtractLogisticsTool -> results.addAll(toolSet.getResults())
        is ExtractBankTransactionTool -> results.addAll(toolSet.getResults())
    }
}
```

- [ ] **Step 2: Add extractBankTransaction tool definition to CloudClassifier**

In `CloudClassifier.kt`, add after `buildExtractLogisticsToolDefinition()`:

```kotlin
private fun buildExtractBankTransactionToolDefinition(): ToolDefinition {
    return ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "extractBankTransaction",
            description = "Extract transaction details from a bank SMS about money debited or credited",
            parameters = FunctionParameters(
                type = "object",
                properties = mapOf(
                    "index" to PropertyDefinition(type = "integer", description = "1-based index of the notification"),
                    "amount" to PropertyDefinition(type = "number", description = "Transaction amount as a number"),
                    "direction" to PropertyDefinition(type = "string", description = "Transaction direction: debit or credit", enum = listOf("debit", "credit")),
                    "account" to PropertyDefinition(type = "string", description = "Masked bank account number e.g. *3371 or XX023"),
                    "bank" to PropertyDefinition(type = "string", description = "Bank name e.g. HDFC Bank, ICICI Bank"),
                    "merchant" to PropertyDefinition(type = "string", description = "Merchant or payee name from SMS, empty if not present")
                ),
                required = listOf("index", "amount", "direction", "account", "bank", "merchant")
            )
        )
    )
}
```

In `classifyAndExtractBatch()`, add the tool registration after the logistics tool:

```kotlin
if ("bank_transaction" in activeCategories) tools.add(buildExtractBankTransactionToolDefinition())
```

In `saveExtractionResults()`, add the bank_transaction case to the `when` block:

```kotlin
"extractBankTransaction" -> "bank_transaction"
```

And add the `buildExtractionDataJson` case for bank_transaction:

```kotlin
"bank_transaction" -> {
    val data = BankTransactionData(
        amount = args["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        merchant = args["merchant"]?.jsonPrimitive?.content ?: "",
        account = args["account"]?.jsonPrimitive?.content ?: "",
        bank = args["bank"]?.jsonPrimitive?.content ?: "",
        direction = args["direction"]?.jsonPrimitive?.content ?: "debit"
    )
    Json.encodeToString(data)
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/ExtractionToolSet.kt \
       app/src/main/java/com/focal/intelligence/CloudClassifier.kt
git commit -m "feat: add extractBankTransaction tool for on-device and cloud"
```

---

### Task 5: PromptBuilder + Classifier Updates for Mandatory Bank Extraction

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/PromptBuilder.kt`
- Modify: `app/src/main/java/com/focal/intelligence/Classifier.kt`

- [ ] **Step 1: Add bank transaction extraction instruction to PromptBuilder**

In `PromptBuilder.kt`, add a new method:

```kotlin
fun buildBankTransactionAugment(bankFlaggedIndices: List<Int>): String {
    if (bankFlaggedIndices.isEmpty()) return ""
    val indices = bankFlaggedIndices.joinToString(", ") { "[$it]" }
    return "\n\nNotifications at indices $indices are bank transaction SMS. " +
        "You MUST call extractBankTransaction for each of them to extract the " +
        "amount, direction (debit/credit), account number, bank name, and merchant."
}
```

- [ ] **Step 2: Update Classifier to pass bank-flagged indices**

In `Classifier.kt`, modify the `classifyAndExtractBatch` method. After building the prompt, detect which notifications are bank transactions and append the augment:

In the section where `systemPrompt` is built (currently around line 189), change to:

```kotlin
val bankIndices = notifications.mapIndexedNotNull { i, n ->
    if (n.isBankTransaction) i + 1 else null
}
val systemPrompt = PromptBuilder.buildClassificationSystemPrompt(modelManager?.getUserFocus()) +
    PromptBuilder.buildExtractionAugment(extractionTools.keys.toList()) +
    PromptBuilder.buildBankTransactionAugment(bankIndices)
```

Also ensure the `bank_transaction` tool is registered. Before the `inferenceProvider.generateWithTools` call, if there are bank-flagged notifications, add the tool:

```kotlin
if (bankIndices.isNotEmpty()) {
    val bankTool = ExtractionToolFactory.createTools(listOf("bank_transaction"))
    allTools.addAll(bankTool.values)
    extractionTools = extractionTools + bankTool
}
```

Note: `extractionTools` parameter is `Map<String, ToolSet>` — you'll need to make it a `var` or use a local mutable copy.

Similarly, in `CloudClassifier.classifyAndExtractBatch()`, add `"bank_transaction"` to the active categories when bank-flagged notifications exist in the batch:

```kotlin
val hasBankSms = notifications.any { it.isBankTransaction }
val effectiveCategories = if (hasBankSms) {
    (activeCategories + "bank_transaction").distinct()
} else activeCategories
```

Use `effectiveCategories` when building tools and the system prompt augment. Also append the bank transaction augment:

```kotlin
val bankIndices = notifications.mapIndexedNotNull { i, n ->
    if (n.isBankTransaction) i + 1 else null
}
val systemPrompt = PromptBuilder.buildClassificationSystemPrompt(modelManager.getUserFocus()) +
    PromptBuilder.buildExtractionAugment(effectiveCategories) +
    PromptBuilder.buildBankTransactionAugment(bankIndices)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/PromptBuilder.kt \
       app/src/main/java/com/focal/intelligence/Classifier.kt \
       app/src/main/java/com/focal/intelligence/CloudClassifier.kt
git commit -m "feat: mandatory extractBankTransaction in classification prompt"
```

---

### Task 6: TransactionCorrelator

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/TransactionCorrelator.kt`

- [ ] **Step 1: Create TransactionCorrelator**

Create `app/src/main/java/com/focal/intelligence/TransactionCorrelator.kt`:

```kotlin
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
```

- [ ] **Step 2: Add getUnmatchedFinanceExtractions to WidgetRepository**

In `WidgetRepository.kt`, add:

```kotlin
suspend fun getUnmatchedFinanceExtractions(): List<ExtractedDataEntity> {
    return extractedDataDao.getUnmatchedFinance()
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/TransactionCorrelator.kt \
       app/src/main/java/com/focal/data/repository/WidgetRepository.kt
git commit -m "feat: add TransactionCorrelator with amount+direction matching"
```

---

### Task 7: InferenceWorker Pipeline Integration

**Files:**
- Modify: `app/src/main/java/com/focal/worker/InferenceWorker.kt`

- [ ] **Step 1: Add correlation step and bank transaction saving**

Add `TransactionCorrelator` and `TransactionRepository` as constructor parameters:

```kotlin
private val transactionCorrelator: TransactionCorrelator,
private val transactionRepository: TransactionRepository,
```

In `doClassification()`, after the classification + extraction loop (after `Log.d(TAG, "Classified $classified/${pending.size}")`), add bank transaction saving before `runTopicGeneration()`:

```kotlin
// Save bank transactions from extraction results
saveBankTransactions(pending)
```

Add the method:

```kotlin
private suspend fun saveBankTransactions(notifications: List<NotificationEntity>) {
    val bankNotifs = notifications.filter { it.isBankTransaction }
    if (bankNotifs.isEmpty()) return

    // Bank transaction data is saved to extracted_data with category "bank_transaction"
    // by the extraction pipeline. Read it and create TransactionEntity rows.
    for (notif in bankNotifs) {
        val existing = transactionRepository.getByNotificationId(notif.id)
        if (existing != null) continue

        val extractions = widgetRepository.getExtractedDataForNotification(notif.id, "bank_transaction")
        for (extraction in extractions) {
            try {
                val data = kotlinx.serialization.json.Json.parseToJsonElement(extraction.data).jsonObject
                val txn = TransactionEntity(
                    notificationId = notif.id,
                    amount = data["amount"]?.jsonPrimitive?.doubleOrNull ?: continue,
                    direction = data["direction"]?.jsonPrimitive?.content ?: continue,
                    account = data["account"]?.jsonPrimitive?.content ?: "",
                    bank = data["bank"]?.jsonPrimitive?.content ?: "",
                    rawMerchant = data["merchant"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    postedAt = notif.postedAt
                )
                transactionRepository.insert(txn)
                Log.d(TAG, "Created transaction: ₹${txn.amount} ${txn.direction} via ${txn.bank} ${txn.account}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create transaction from ${notif.id}", e)
            }
        }
    }
}
```

Add the required imports:

```kotlin
import com.focal.data.db.entity.TransactionEntity
import com.focal.data.repository.TransactionRepository
import com.focal.intelligence.TransactionCorrelator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
```

In `runTopicGeneration()`, add the correlation step right before widget compute:

```kotlin
try {
    transactionCorrelator.correlate()
    Log.d(TAG, "Transaction correlation complete")
} catch (e: CancellationException) { throw e }
catch (e: Exception) { Log.e(TAG, "Transaction correlation failed", e) }
```

- [ ] **Step 2: Add getExtractedDataForNotification to WidgetRepository**

In `WidgetRepository.kt`, add:

```kotlin
suspend fun getExtractedDataForNotification(notificationId: String, category: String): List<ExtractedDataEntity> {
    return extractedDataDao.getByNotificationIdAndCategory(notificationId, category)
}
```

In `ExtractedDataDao.kt`, add:

```kotlin
@Query("SELECT * FROM extracted_data WHERE notification_id = :notificationId AND category = :category")
suspend fun getByNotificationIdAndCategory(notificationId: String, category: String): List<ExtractedDataEntity>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/worker/InferenceWorker.kt \
       app/src/main/java/com/focal/data/repository/WidgetRepository.kt \
       app/src/main/java/com/focal/data/db/dao/ExtractedDataDao.kt
git commit -m "feat: integrate bank transaction saving + correlation into InferenceWorker"
```

---

### Task 8: WidgetComputeEngine — Finance Reads from Transactions

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/WidgetComputeEngine.kt`

- [ ] **Step 1: Add TransactionRepository to constructor and update finance compute**

Update the class constructor:

```kotlin
class WidgetComputeEngine(
    private val widgetRepository: WidgetRepository,
    private val transactionRepository: TransactionRepository
)
```

Add a new method for computing finance from transactions:

```kotlin
private suspend fun computeFinanceFromTransactions(config: WidgetConfigEntity): WidgetStateEntity {
    val transactions = transactionRepository.getAll()
    if (transactions.isEmpty()) {
        return WidgetStateEntity(
            widgetId = config.id,
            headline = "₹0",
            itemCount = 0
        )
    }

    val debits = transactions.filter { it.direction == "debit" }
    val totalSpent = debits.sumOf { it.amount }
    val formatted = "₹${"%.0f".format(totalSpent)}"

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

    val merchantCount = grouped.size
    val unassignedCount = transactions.count { it.matchedNotificationId == null }
    val subtitle = buildString {
        append("Across $merchantCount merchants")
        if (unassignedCount > 0) append(" · $unassignedCount unassigned")
    }

    val sourceApps = transactions.mapNotNull { it.matchedApp }.distinct()

    return WidgetStateEntity(
        widgetId = config.id,
        headline = formatted,
        subtitle = subtitle,
        badge = badge,
        detailJson = kotlinx.serialization.json.Json.encodeToString(detailLines),
        sourceAppIcons = kotlinx.serialization.json.Json.encodeToString(sourceApps),
        itemCount = transactions.size
    )
}
```

Update `computeWidget()` to use transactions for finance:

```kotlin
private suspend fun computeWidget(config: WidgetConfigEntity): WidgetStateEntity {
    if (config.category == "finance") {
        return computeFinanceFromTransactions(config)
    }
    // ... existing code for other categories ...
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/WidgetComputeEngine.kt
git commit -m "feat: finance widget reads from transactions table"
```

---

### Task 9: PulseDetailViewModel + PulseDetailScreen for Transactions

**Files:**
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseDetailViewModel.kt`
- Modify: `app/src/main/java/com/focal/ui/pulse/PulseDetailScreen.kt`

- [ ] **Step 1: Update PulseDetailViewModel to load transactions for finance**

Add `TransactionRepository` to constructor:

```kotlin
private val transactionRepository: TransactionRepository
```

Update `PulseDetailUiState` to include transactions:

```kotlin
data class PulseDetailUiState(
    val config: WidgetConfigEntity? = null,
    val state: WidgetStateEntity? = null,
    val rows: List<ExtractedDataEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val isLoading: Boolean = true
)
```

Update `loadWidget()` to load transactions for finance:

```kotlin
private fun loadWidget() {
    viewModelScope.launch {
        val config = widgetRepository.getConfig(widgetId)
        val rows = if (config != null && config.category != "finance") {
            widgetRepository.getExtractedData(config.category)
        } else emptyList()
        val transactions = if (config != null && config.category == "finance") {
            transactionRepository.getAll()
        } else emptyList()
        _uiState.value = _uiState.value.copy(
            config = config,
            rows = rows,
            transactions = transactions,
            isLoading = false
        )
    }
}
```

Update `onDismissRow` — for finance, dismiss a transaction:

```kotlin
fun onDismissTransaction(txn: TransactionEntity) {
    commitPendingDelete()

    pendingDeleteTransaction = txn
    _uiState.value = _uiState.value.copy(
        transactions = _uiState.value.transactions.filter { it.id != txn.id }
    )
    _events.tryEmit(PulseDetailEvent.ShowUndo("Removed"))

    deleteJob = viewModelScope.launch {
        delay(3000)
        commitPendingDelete()
    }
}

fun onUndoTransaction() {
    deleteJob?.cancel()
    val restored = pendingDeleteTransaction ?: return
    pendingDeleteTransaction = null
    _uiState.value = _uiState.value.copy(
        transactions = (_uiState.value.transactions + restored).sortedByDescending { it.postedAt }
    )
}
```

Add `pendingDeleteTransaction` field:

```kotlin
private var pendingDeleteTransaction: TransactionEntity? = null
```

Update `commitPendingDelete()` to handle both types:

```kotlin
private fun commitPendingDelete() {
    pendingDeleteTransaction?.let { txn ->
        pendingDeleteTransaction = null
        deleteJob?.cancel()
        viewModelScope.launch {
            transactionRepository.deleteById(txn.id)
            widgetComputeEngine.computeAll()
            reloadData()
        }
        return
    }
    // ... existing extracted data delete logic ...
}
```

Add `reloadData()`:

```kotlin
private suspend fun reloadData() {
    val config = _uiState.value.config ?: return
    if (config.category == "finance") {
        val transactions = transactionRepository.getAll()
        _uiState.value = _uiState.value.copy(transactions = transactions)
    } else {
        val rows = widgetRepository.getExtractedData(config.category)
        _uiState.value = _uiState.value.copy(rows = rows)
    }
}
```

- [ ] **Step 2: Update PulseDetailScreen to show transaction rows**

In the breakdown section of `PulseDetailScreen.kt`, replace the single items block with a conditional:

```kotlin
val isFinance = config.category == "finance"

if (isFinance && state.transactions.isNotEmpty()) {
    item { SectionHeader(title = "TRANSACTIONS") }
    items(
        items = state.transactions,
        key = { it.id }
    ) { txn ->
        SwipeToDismissTransactionRow(
            txn = txn,
            onDismiss = { viewModel.onDismissTransaction(txn) }
        )
    }
} else if (!isFinance && state.rows.isNotEmpty()) {
    item { SectionHeader(title = "BREAKDOWN") }
    items(
        items = state.rows,
        key = { it.id }
    ) { row ->
        SwipeToDismissRow(
            row = row,
            category = config.category,
            onDismiss = { viewModel.onDismissRow(row) }
        )
    }
}
```

Update the undo handler to handle both types:

```kotlin
is PulseDetailEvent.ShowUndo -> {
    val result = snackbarHostState.showSnackbar(
        message = event.message,
        actionLabel = "Undo",
        duration = SnackbarDuration.Short
    )
    if (result == SnackbarResult.ActionPerformed) {
        viewModel.onUndo()
        viewModel.onUndoTransaction()
    }
}
```

Add the `SwipeToDismissTransactionRow` composable:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissTransactionRow(
    txn: TransactionEntity,
    onDismiss: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onDismiss()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceContainer
                },
                label = "swipeBg"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(color, MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        enableDismissFromEndToStart = false
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small
        ) {
            val merchant = txn.matchedMerchant?.takeIf { it.isNotBlank() }
                ?: txn.rawMerchant?.takeIf { it.isNotBlank() }
                ?: "Unassigned"
            val accountLabel = "${txn.bank} ${txn.account}"
            val prefix = if (txn.direction == "credit") "+" else "-"
            val amountText = "${prefix}₹${"%.0f".format(txn.amount)}"

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(merchant, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        accountLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    amountText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (txn.direction == "credit")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
```

Add the import for `TransactionEntity`:

```kotlin
import com.focal.data.db.entity.TransactionEntity
```

Update the notification count at the bottom:

```kotlin
if (widgetState != null) {
    val count = if (isFinance) state.transactions.size else state.rows.size
    item {
        Spacer(Modifier.height(8.dp))
        Text(
            "$count ${if (isFinance) "transactions" else "notifications"} · updated ${formatRelativeTime(widgetState.lastUpdatedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/pulse/PulseDetailViewModel.kt \
       app/src/main/java/com/focal/ui/pulse/PulseDetailScreen.kt
git commit -m "feat: finance detail shows transaction rows with bank account info"
```

---

### Task 10: Dependency Injection Wiring

**Files:**
- Modify: `app/src/main/java/com/focal/di/IntelligenceModule.kt`
- Modify: `app/src/main/java/com/focal/di/DatabaseModule.kt` (or wherever DAOs are provided)

- [ ] **Step 1: Add TransactionDao, TransactionRepository, TransactionCorrelator providers**

In `IntelligenceModule.kt`, add:

```kotlin
@Provides
@Singleton
fun provideTransactionRepository(
    transactionDao: TransactionDao,
    extractedDataDao: ExtractedDataDao
): TransactionRepository {
    return TransactionRepository(transactionDao, extractedDataDao)
}

@Provides
@Singleton
fun provideTransactionCorrelator(
    transactionRepository: TransactionRepository,
    widgetRepository: WidgetRepository
): TransactionCorrelator {
    return TransactionCorrelator(transactionRepository, widgetRepository)
}
```

Add the TransactionDao provider in the database module (find where other DAOs like `WidgetConfigDao`, `ExtractedDataDao` are provided — likely `DatabaseModule.kt`):

```kotlin
@Provides
fun provideTransactionDao(database: FocalDatabase): TransactionDao {
    return database.transactionDao()
}
```

Update `WidgetComputeEngine` provider to include `TransactionRepository`:

```kotlin
@Provides
@Singleton
fun provideWidgetComputeEngine(
    widgetRepository: WidgetRepository,
    transactionRepository: TransactionRepository
): WidgetComputeEngine {
    return WidgetComputeEngine(widgetRepository, transactionRepository)
}
```

Add imports:

```kotlin
import com.focal.data.db.dao.TransactionDao
import com.focal.data.repository.TransactionRepository
import com.focal.intelligence.TransactionCorrelator
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/di/IntelligenceModule.kt \
       app/src/main/java/com/focal/di/DatabaseModule.kt
git commit -m "feat: wire TransactionDao, TransactionRepository, TransactionCorrelator in DI"
```

---

### Task 11: Build Verification

- [ ] **Step 1: Build the project**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. Fix any compilation errors.

- [ ] **Step 2: Install and verify on device**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify:
1. App launches without crash (migration v8→v9 runs)
2. Tune screen still works
3. Pulse screen still works
4. Send a test bank SMS to the device and verify it gets flagged + extracted

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: bank SMS source of truth for finance widget — complete"
```
