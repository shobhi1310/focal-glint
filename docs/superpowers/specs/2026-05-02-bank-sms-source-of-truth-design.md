# Bank SMS as Source of Truth for Money Movement

## Goal

Use bank debit/credit SMS messages as the authoritative record of money flow in the Finance Pulse widget. Every real transaction produces a bank SMS — card swipes, UPI, net banking, mandates, ATM withdrawals. App notifications (GPay, PhonePe, Swiggy) are secondary: they enrich the transaction with merchant name and category, but the bank SMS is the truth.

## Architecture

Two-tier extraction in a single LLM pass, followed by pure-code correlation:

1. **Tier 1 (mandatory):** Bank SMS → `extractBankTransaction` tool call. Always fires for bank-flagged notifications. Produces a `TransactionEntity` row.
2. **Tier 2 (optional):** App notifications → `extractFinance` tool call. LLM decides whether to call. Produces `ExtractedDataEntity` rows (existing behavior).
3. **Correlation (pure code):** Match Tier 1 transactions to Tier 2 extractions by exact amount + same direction. Time proximity as tiebreaker for duplicate amounts.

## Bank SMS Detection Gate

Deterministic check in `FocalNotificationListener`, no LLM needed:

```
IS BANK TRANSACTION when ALL of:
  - packageName == "com.google.android.apps.messaging"
  - title matches bank sender pattern: /^[A-Z]{2}-[A-Z]{3,}/ 
    (covers AD-HDFCBK, AX-AXISBK, JX-HDFCBK, JD-HDFCBN, VM-KOTAKB, etc.)
  - content contains ANY of: "debited", "credited", "sent Rs", "received Rs",
    "Rs.", "INR ", "withdrawn", "transferred", "mandate", "EMI deducted"
  - content does NOT contain ANY of: "OTP", "One Time Password", "verification",
    "verification code", "login"
```

Result: a boolean `is_bank_transaction` column on `NotificationEntity`. Set at capture time, before any LLM runs.

## New Tool: extractBankTransaction

Added to the tool set alongside `classifyNotification` and `extractFinance`. The system prompt instructs: "For any notification flagged as a bank transaction, you MUST call extractBankTransaction."

```
extractBankTransaction(
    index: Int,          // 1-based notification index
    amount: Double,      // Transaction amount
    direction: String,   // "debit" or "credit"
    account: String,     // Masked account e.g. "*3371" or "XX023"
    bank: String,        // Bank name e.g. "HDFC Bank"
    merchant: String     // Raw merchant from SMS if present, empty if not
)
```

This runs in the SAME LLM inference pass as classification — no extra KV cache contention. The tool is registered alongside other tools in the batch. The LLM sees all notifications (bank SMS + app notifications) in one prompt and handles classification + extraction in a single conversation.

## New Table: transactions

```sql
CREATE TABLE transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    notification_id TEXT NOT NULL,           -- bank SMS notification
    amount REAL NOT NULL,
    direction TEXT NOT NULL,                 -- "debit" or "credit"
    account TEXT NOT NULL,                   -- masked account
    bank TEXT NOT NULL,                      -- bank name
    raw_merchant TEXT,                       -- raw from SMS (may be cryptic)
    matched_notification_id TEXT,            -- app notification (nullable = unassigned)
    matched_app TEXT,                        -- package name of matched app
    matched_merchant TEXT,                   -- clean merchant from app extraction
    category TEXT,                           -- food/transport/shopping/bills/transfer/other
    posted_at INTEGER NOT NULL,             -- bank SMS timestamp
    matched_at INTEGER                       -- when correlation happened
);
```

## Correlation Engine

Pure code, runs after classification + extraction, before widget compute. No LLM.

```
Algorithm:
1. Get unmatched transactions (matched_notification_id IS NULL)
2. Get unmatched finance extractions from extracted_data
3. For each unmatched transaction:
   a. Find extractions with EXACT amount AND SAME direction
   b. One match → pair them (write matched_notification_id, matched_app, matched_merchant, category)
   c. Multiple matches → pick the one with closest posted_at BEFORE the bank SMS
   d. Zero matches → stays unassigned (real transaction, no app trail)
4. Mark matched extractions so they don't get re-matched
```

## Pipeline Integration (InferenceWorker)

```
Step 1: Classification + Extraction (single LLM pass)
  - Regular notifications → classifyNotification + optional tool calls
  - Bank SMS (is_bank_transaction=true) → classifyNotification + MANDATORY extractBankTransaction
  - App finance notifications → classifyNotification + optional extractFinance

Step 2: Topic generation + embedding (existing)

Step 3: Correlation (NEW, pure code)
  - Match transactions to extracted_data by amount + direction

Step 4: Widget compute (MODIFIED)
  - Finance widget reads from transactions table

Step 5: Narrative generation (existing, LOW priority)
```

## Finance Widget Changes

**Reads from `transactions` table instead of `extracted_data` for finance.**

### Pulse card (compact):
- Headline: `₹{sum of debits}` (real money spent)
- Badge: Latest transaction e.g. `-₹1,759 BookMyShow`
- Subtitle: `Across {N} merchants`

### Detail screen (breakdown rows):
Each row = one TransactionEntity:

| Left side | Right side |
|-----------|------------|
| BookMyShow *(HDFC *3371)* | -₹1,759 |
| Google One *(HDFC *3371)* | -₹499 |
| Unassigned *(ICICI *0023)* | -₹3,200 |
| Salary *(HDFC *3371)* | +₹45,000 |

- Matched transactions: show app merchant name + bank account
- Unmatched: show "Unassigned" + raw_merchant if available + bank account
- Credits: green with `+` prefix
- Debits: default with `-` prefix
- Swipe-to-delete works (deletes transaction row)

## Room Migration

Migration v8 → v9:
1. Add `is_bank_transaction` BOOLEAN column to `notifications` (default 0)
2. Create `transactions` table
3. Add `matched_transaction_id` INTEGER column to `extracted_data` (nullable, default NULL) — tracks which transaction this extraction was correlated to, prevents re-matching

## Files Changed

### New files:
- `TransactionEntity.kt` — Room entity
- `TransactionDao.kt` — Room DAO (insert, getUnmatched, updateMatch, getAll, deleteById)
- `TransactionCorrelator.kt` — Pure code matching engine
- `BankSmsDetector.kt` — Deterministic gate logic (static methods)

### Modified files:
- `FocalNotificationListener.kt` — Call BankSmsDetector, set is_bank_transaction flag
- `NotificationEntity.kt` — Add is_bank_transaction column
- `FocalDatabase.kt` — Add TransactionDao, migration v8→v9
- `InferenceWorker.kt` — Add correlation step between extraction and widget compute
- `Classifier.kt` / `CloudClassifier.kt` — Register extractBankTransaction tool for bank-flagged batches
- `PromptBuilder.kt` — Add bank transaction extraction instruction to system prompt
- `WidgetComputeEngine.kt` — Finance widget reads from transactions table
- `PulseDetailScreen.kt` — Updated row display for transaction format
- `PulseDetailViewModel.kt` — Load from transactions for finance widgets
- `IntelligenceModule.kt` — Provide TransactionCorrelator, TransactionDao
- `WidgetRepository.kt` or new `TransactionRepository.kt` — Transaction CRUD

### Existing behavior preserved:
- `extracted_data` finance rows still created from app notifications (used as correlation candidates)
- Non-finance widgets (work, personal, logistics) unchanged
- Classification pipeline unchanged
- Narrative generation unchanged
