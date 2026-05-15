---
title: Extraction & widgets
description: How the LLM extracts structured data from notifications and how Pulse widgets compute answers from it
---

# Extraction & widgets

Focal doesn't just classify and summarize. It pulls structured data out of notifications . amounts, merchants, senders, delivery statuses . and uses it to power live-updating widgets on the Pulse dashboard.

---

## Extraction overview

Extraction happens in the same inference pass as classification. When `classifyAndExtractBatch()` runs, the model is given both the classification tool and the extraction tools. It first classifies each notification, then calls extraction tools only on the ones it marks as matters.

The extraction tools are defined in `ExtractionToolSet.kt` using LiteRT-LM's tool framework.

---

## Finance extraction

```kotlin
@Tool("Extract financial transaction data from a notification about money, payments, or banking")
fun extractFinance(
    @ToolParam("1-based index of the notification") index: Int,
    @ToolParam("Transaction amount as a number") amount: Double,
    @ToolParam("Merchant or payee name") merchant: String,
    @ToolParam("Spending category: food, transport, shopping, bills, transfer, or other") category: String,
    @ToolParam("Transaction direction: debit or credit") direction: String
): Map<String, Any>
```

**Extracted data shape:**
```json
{
  "amount": 450.0,
  "merchant": "Swiggy",
  "category": "food",
  "direction": "debit"
}
```

Catches UPI payment notifications, wallet transactions, and any notification about money movement.

---

## Work extraction

```kotlin
@Tool("Call only for software or project workflow events that reference a concrete trackable item")
fun extractWork(
    @ToolParam("1-based index of the notification") index: Int,
    @ToolParam("The specific item: PR number, issue ID, build name, or ticket reference") entity: String,
    @ToolParam("Person who triggered the event") sender: String,
    @ToolParam("Action: review_requested, merged, commented, assigned, build_failed, build_passed, mentioned") action: String,
    @ToolParam("Repository or project name if present") repo: String
): Map<String, Any>
```

**Extracted data shape:**
```json
{
  "entity": "#342",
  "sender": "a teammate",
  "action": "review_requested",
  "repo": "a repository"
}
```

The tool description explicitly tells the model to skip social network activity, generic chat, and meeting invites. Only concrete trackable items (PRs, issues, builds) qualify.

---

## Personal extraction

```kotlin
@Tool("Call only when a real named person directly sent the user a 1-on-1 message or placed a call")
fun extractPersonal(
    @ToolParam("1-based index of the notification") index: Int,
    @ToolParam("Full name of the person . never a generic label") sender: String,
    @ToolParam("How they reached out: call, message, email, or dm") channel: String,
    @ToolParam("Number of messages or call attempts, default 1") count: Int,
    @ToolParam("Brief snippet of what they said, if visible") snippet: String
): Map<String, Any>
```

**Extracted data shape:**
```json
{
  "sender": "A Contact",
  "channel": "message",
  "count": 3,
  "snippet": "Call me when free"
}
```

The validation in `WidgetComputeEngine.isValidRow()` filters out generic senders:
```kotlin
private val GENERIC_SENDER_LABELS = setOf(
    "citizen", "user", "customer", "member", "system",
    "you", "me", "unknown", "sender", "admin"
)
```

If the sender label is generic or starts with `"app:"`, the row is excluded from widgets . these are system/broadcast messages, not real people.

---

## Logistics extraction

```kotlin
@Tool("Call only when an order the user placed is actively moving through delivery")
fun extractLogistics(
    @ToolParam("1-based index of the notification") index: Int,
    @ToolParam("What is being delivered . item name or order description") item: String,
    @ToolParam("The seller or delivery service handling the shipment") merchant: String,
    @ToolParam("Status: shipped, out_for_delivery, delivered, delayed, or cancelled") status: String,
    @ToolParam("Estimated minutes until arrival, or -1 if not stated") etaMinutes: Int
): Map<String, Any>
```

**Extracted data shape:**
```json
{
  "item": "Wireless Earbuds",
  "merchant": "Amazon",
  "status": "out_for_delivery",
  "etaMinutes": 45
}
```

---

## Bank transaction extraction

```kotlin
@Tool("Extract transaction details from a bank SMS about money debited or credited")
fun extractBankTransaction(
    @ToolParam("1-based index of the notification") index: Int,
    @ToolParam("Transaction amount as a number") amount: Double,
    @ToolParam("Transaction direction: debit or credit") direction: String,
    @ToolParam("Masked bank account number e.g. XX023") account: String,
    @ToolParam("Bank name e.g. HDFC, ICICI") bank: String,
    @ToolParam("Merchant or payee name from SMS, empty if not present") merchant: String
): Map<String, Any>
```

**Extracted data shape:**
```json
{
  "amount": 5000.0,
  "direction": "debit",
  "account": "XX123",
  "bank": "Sample Bank",
  "merchant": ""
}
```

### Bank SMS detection

Before extraction, `BankSmsDetector` pre-screens notifications:

```kotlin
object BankSmsDetector {
    // Pattern: sender shortcode like "AX-BANKCD", "JD-BANKNM"
    private val BANK_SENDER_PATTERN = Regex("^[A-Z]{2}-[A-Za-z]{3,}")

    // Keywords: debited, credited, sent rs, withdrawn, transferred, etc.
    private val TRANSACTION_KEYWORDS = listOf("debited", "credited", "sent rs", .)

    // Exclusions: OTP, verification, login (don't extract auth messages)
    private val EXCLUSION_KEYWORDS = listOf("otp", "one time password", .)

    fun isBankTransaction(packageName: String, title: String, content: String): Boolean
}
```

Only works with the Google Messages app (`com.google.android.apps.messaging`). This prevents false positives from banks' own apps.

---

## NoExtraction tool

A special tool that the model must call when no extraction category applies:

```kotlin
@Tool("Call for every 'matters' notification when none of the other extraction tools apply")
fun noExtraction(
    @ToolParam("1-based index of the notification") index: Int,
    @ToolParam("Short snake_case reason") reason: String
): Map<String, Any>
```

This forces the model to explicitly acknowledge every matters notification. If a notification doesn't get any extraction tool call and doesn't get `noExtraction`, we know the model missed it.

---

## ExtractionResultSaver

Takes the LLM's tool call results and persists them:

```kotlin
class ExtractionResultSaver(private val widgetRepository: WidgetRepository?) {
    suspend fun save(extractionResults: List<ExtractionResult>, notifications: List<NotificationEntity>) {
        val entities = extractionResults.mapNotNull { result ->
            val notification = notifications.getOrNull(result.notificationIndex - 1) ?: return@mapNotNull null
            ExtractedDataEntity(
                notificationId = notification.id,
                category = result.category,
                data = result.dataJson,
                appPackage = notification.packageName
            )
        }
        widgetRepository.saveExtractedData(entities)
    }
}
```

Each extraction row is linked to its source notification via `notificationId`, enabling traceability back to the original notification text.

---

## WidgetComputeEngine

Takes extracted data and computes widget answers. Runs as the last step in every inference cycle.

### Operations

| Operation | Function | Description |
|---|---|---|
| `SUM` | `computeSum()` | Totals a numeric field across all rows |
| `COUNT` | `computeCount()` | Counts matching rows |
| `LATEST` | `computeLatest()` | Shows the most recent entry |
| `LIST` | `computeList()` | Groups by a field, shows top groups |
| `MAX` | `computeMax()` | Finds the maximum value |
| `STATUS` | `computeStatus()` | Groups by status field |

### Finance widgets (special handling)

Finance widgets merge data from two sources:
1. **Extracted data** (category = `finance`) . from UPI payment notifications
2. **Transactions** (from `TransactionEntity`) . from bank SMS extraction

```kotlin
private suspend fun computeFinanceFromTransactions(config): WidgetStateEntity {
    val transactions = transactionRepository.getAll()
    val totalSent = transactions.filter { it.direction == "debit" }.sumOf { it.amount }
    val totalReceived = transactions.filter { it.direction == "credit" }.sumOf { it.amount }
    val net = totalReceived - totalSent

    return WidgetStateEntity(
        headline = "+₹${net}",                // net position
        subtitle = "₹${totalSent} sent · ₹${totalReceived} received",
        badge = "${latestPrefix}₹${latest.amount} ${latestMerchant}",
        detailJson = Json.encodeToString(merchantBreakdown)
    )
}
```

### Grouped detail lines

Most operations produce `detailJson` . a JSON array of label/value pairs for the drill-down view:

```json
[
  {"label": "Swiggy", "value": "₹450"},
  {"label": "Amazon", "value": "₹1,200"},
  {"label": "Uber", "value": "₹350"}
]
```

### Source app icons

Each widget state includes `sourceAppIcons` . a JSON array of package names:

```json
["com.google.android.apps.nbu.paisa.user", "com.phonepe.app"]
```

These are rendered as a row of app icons on the Pulse card, showing which apps contributed data.

---

## TransactionCorrelator

Matches bank transaction merchants to known apps:

```kotlin
class TransactionCorrelator(private val transactionRepository: TransactionRepository) {
    suspend fun correlate() {
        // Match raw merchants from bank SMS to UPI apps
        // e.g., a food delivery merchant matching to its payment app
    }
}
```

This enables the Pulse detail screen to show "paid via payment app" next to a bank transaction.

---

## Pulse wizard

Users create widgets through `PulseWizard`:

1. **Pick a question** from templates or type custom text
2. **Select category** . finance, work, personal, logistics
3. **Choose operation** . sum, count, latest, etc.
4. **Optional app filters** . limit to notifications from specific apps

The wizard creates a `WidgetConfigEntity` row. From that point on, the `WidgetComputeEngine` updates the widget's state every inference cycle.

**Template questions:**
```
Finance:  "How much did I spend?"      → SUM of amounts
Work:     "Any PR reviews waiting?"    → LATEST with action=review_requested
Personal: "Who reached out?"           → LIST grouped by sender
Logistics:"Where are my orders?"       → STATUS grouped by delivery status
```

---

## Validation rules

`WidgetComputeEngine.isValidRow()` validates each extracted data row before computing:

```kotlin
fun isValidRow(row: ExtractedDataEntity, category: String): Boolean {
    val field = when (category) {
        "work" -> "entity"
        "personal" -> "sender"
        "logistics" -> "item"
        else -> return true  // finance has no required field
    }
    val value = parseField(row.data, field) ?: return false
    if (value.length <= 1 || !value.any { it.isLetterOrDigit() }) return false
    if (category == "personal") {
        val lower = value.lowercase().trim()
        if (lower in GENERIC_SENDER_LABELS || lower.startsWith("app:")) return false
    }
    return true
}
```

---

## What to read next

- [Classification & rules](classification-and-rules.html) . classification happens in the same LLM pass
- [Embeddings & clustering](embeddings-and-clustering.html) . how matters notifications are clustered
- [LLM inference pipeline](inference-pipeline.html) . how the model is invoked
- [Architecture & internals](architecture.html) . widget_configs and widget_state tables
