---
title: Fine-tuning guide
description: How Focal's Gemma 4 E2B model is fine-tuned for notification triage and extraction
---

# Fine-tuning guide

Focal's on-device model is a fine-tuned version of Gemma 4 E2B. The base model is great at general reasoning, but Focal needs it to be excellent at one specific thing: reading a batch of Android notifications and emitting structured tool calls that classify and extract data.

This page explains the entire pipeline . dataset design, how records are built, training configuration, and verification.

---

## Why fine-tune?

Gemma 4 E2B is 2.58 GB and runs locally. The base model can classify and extract when given good prompts, but fine-tuning makes it:

- **Reliable** . every notification gets classified, no missed indices
- **Consistent** . uses the exact snake_case reasons the app expects
- **Efficient** . lower temperature at inference, faster output, fewer tokens wasted on prose
- **Dedup-aware** . learns to collapse cross-channel echoes (same transaction across SMS + email + app push)

Fine-tuning was done using [Unsloth](https://github.com/unslothai/unsloth) on Colab with LoRA adapters.

---

## What the model learns

For a batch of up to 10 Android notifications, the model emits two kinds of tool calls:

1. **`classifyNotification(index, category, reason)`** . exactly once per notification
2. **`extract<Category>(.)`** . only for `matters` notifications, only when parseable data exists

The model outputs **tool calls only. No prose.** This keeps inference fast and output deterministic.

### The five tools

| Tool | Purpose |
|---|---|
| `classifyNotification` | Label each notification `matters` or `noise` with a reason |
| `extractFinance` | Pull out amount, merchant, category, direction |
| `extractWork` | Pull out entity, sender, action, repo |
| `extractPersonal` | Pull out sender, channel, count, snippet |
| `extractLogistics` | Pull out item, merchant, status, ETA |

---

## Dataset format

Training data lives in a JSONL file . one JSON object per line. Each record is a complete training example:

```json
{
  "id": "batch-0001-classify",
  "messages": [
    {"role": "system", "content": "<system prompt>"},
    {"role": "user", "content": "<batch of 10 notifications>"},
    {"role": "assistant", "content": "", "tool_calls": [.]}
  ],
  "tools": [.]
}
```

**Key invariants:**
- `assistant.content` is always the empty string `""` . never `null`, never prose
- System prompts are byte-identical across all records of the same shape
- Tool call IDs are sequential (`call_1`, `call_2`, .) within the assistant turn
- Classification calls are in index order; extract calls follow, grouped by parent index

---

## Two record shapes

Each source batch of 10 notifications produces exactly two training records, with identical user content but different system prompts and tool lists.

### Shape A . classify only

The model learns classification in isolation.

**System prompt:**

> You are a notification triage assistant. Your job is to decide whether each notification meaningfully adds value to the user's day or just demands their attention without giving anything back. For every [index] in the list, call classifyNotification exactly once with that same index. Mark it 'matters' if a thoughtful person would want to know about it now . something asks for their attention, response, awareness, or money. Mark it 'noise' if it exists to pull the user into an app, sell them something, surface algorithmic content, or repeat what they already know. Use a short snake_case reason. Output tool calls only . no prose.

**Tools available:** `classifyNotification` only.

**Assistant output:** N `classifyNotification` calls (one per notification index).

### Shape B . classify + extract

The model learns to classify AND extract in a single pass.

**System prompt:** Shape A prompt + extract suffix:

> For every notification you marked 'matters', also call the appropriate extraction tool(s) so the user's widgets can show what happened. A single notification may trigger multiple extraction tools when it contains multiple distinct things. When the same real-world event appears across several notifications (echoed across SMS, email, or app pushes), call the extraction tool only once for the most authoritative source. Available extraction categories: finance, work, personal, logistics. Output tool calls only.

**Tools available:** All five (classifyNotification + 4 extract tools).

**Assistant output:** N `classifyNotification` calls + M extract calls (only for matters notifications).

### Why two shapes?

Shape A teaches the model to focus on classification. Shape B teaches it to switch between classification and extraction in one pass. The model sees both shapes during training, so at inference time it works regardless of whether extraction tools are available.

---

## Classification rules

The dataset follows strict labeling conventions. Every notification in the batch gets one of these outcomes:

### Matters triggers

A notification is `matters` when a thoughtful person would want to know about it now:

- Real human messages (chat, SMS from a contact, missed calls)
- Work items (meetings, mentions, PR reviews, assigned tasks)
- OTPs from banks or services the user uses
- Bank or card transactions (debit, credit, refund)
- Confirmed deliveries and order updates
- Calendar invites from real people
- Security or login alerts
- Email from a person (one-to-one, not broadcast)

### Noise triggers

A notification is `noise` when it exists to pull the user into an app:

- Marketing, promotional, "come back!" nudges
- News feeds, weather, social media posts and reactions
- Broadcast channels and business accounts
- Statement-only emails with no actual transaction
- System status notifications (battery, screenshot saved, "marked as read")
- HR broadcasts and workplace newsletters
- App-side echoes of bank transactions (the bank SMS is authoritative, not the app push)
- Reactions on messages ("Reacted 😂", "Liked your message")

### Reason vocabulary

The model learns a controlled vocabulary of snake_case reasons:

| Reason | Used for |
|---|---|
| `direct_message_or_chat` | Real human 1-on-1 messages |
| `work_message_or_meeting` | Work IM/meeting alerts |
| `work_email_or_meeting` | Work email threads |
| `email_from_person` | Personal 1-to-1 email |
| `missed_call` | Missed phone calls |
| `delivery_update` | Order/delivery status changes |
| `sms_message_or_alert` | Transactional SMS |
| `email_transaction_message_invite_or_security` | Transactional email |
| `commercial_app_marketing_or_engagement` | App marketing nudges |
| `email_newsletter_marketing_or_feed` | Email marketing |
| `news_social_feed_or_weather` | Social media feeds |
| `messaging_app_promotional_spam_or_feed` | Business broadcasts on chat apps |
| `sms_promotion` | Promotional SMS |
| `finance_app_marketing` | Finance app marketing |
| `empty_or_status_only` | Empty or system status notifications |

---

## Extraction rules

Extract tools fire **only for matters notifications**, and only when the notification has parseable fields.

### Finance extraction

Fires when the source is a bank, card issuer, payment gateway, or refund/cashback message AND amount + direction are parseable.

**Cross-channel dedup:** The same transaction often arrives as bank SMS + bank Gmail alert + payment app push. The dataset teaches the model to emit ONE `extractFinance` call per transaction, picking the most authoritative source:

| Priority | Source |
|---|---|
| 1 (highest) | Bank SMS |
| 2 | Bank's own Gmail alert |
| 3 | Third-party receipt (Razorpay, etc.) |
| 4 (lowest) | In-app push notification |

Dedup key: `(rounded amount, direction)`.

### Work extraction

Teams/Outlook only. Dedup by `(sender, entity)` . same person acting on the same item → one call.

### Personal extraction

Real chats, calls, and email-from-person. Dedup by `(sender, channel)` . collapsed into one call with `count=N` and the latest snippet.

Generic sender labels are excluded: `citizen`, `user`, `customer`, `member`, `system`, `admin`. If no real human name is present, the extract tool does not fire.

### Logistics extraction

Delivery and order updates with a clear merchant + status. Dedup by merchant . picks the most advanced status in the batch:

```
delivered > out_for_delivery > shipped > ordered > cancelled
```

Example: A Swiggy order producing 4 notifications ("Arrived at Restaurant" → "Timely Pick Up" → "Order arrived!" → "Delivered!") results in ONE `extractLogistics` call with `status=delivered`.

### Matters with no extract

A matters notification emits zero extract calls when no widget category applies. The model learns this explicitly: standalone OTPs, statement notices without amounts, and receipt emails where the amount isn't surfaced are classified as matters but have no extract call.

---

## Data pipeline

### Source

The input is a notification dump file produced by the Android app's debug logging. Each entry contains notification metadata: app name, title, body text, package name, timestamp, category, and classification status.

### Pipeline scripts

| Script | Purpose |
|---|---|
| `append_dataset.py` | State management . tracks offset, provides batch windows |
| `append_dataset_records.py` | Record builder . takes a batch spec and produces JSONL records |
| `normalize_dataset.py` | Idempotent fix-up . rewrites all records to canonical form |
| `verify_dataset.py` | End-to-end verifier . checks script/Kotlin alignment and dataset invariants |

### Loop

For each batch of 10 notifications:

1. **Read** next 10 notifications from the dump file
2. **Classify** each as matters/noise with a snake_case reason
3. **Extract** structured data from matters notifications following dedup rules
4. **Build** two JSONL records (Shape A + Shape B) via `append_dataset_records.py`
5. **Append** to the training file
6. **Advance** offset and repeat until all notifications are processed

### Record builder behavior

`append_dataset_records.py` accepts a batch spec in this format:

```json
{
  "classifications": [
    {"index": 1, "category": "matters", "reason": "direct_message_or_chat"},
    {"index": 2, "category": "noise", "reason": "commercial_app_marketing_or_engagement"}
  ],
  "extracts": [
    {"name": "extractPersonal", "arguments": {"index": 1, "sender": ".", "channel": "message", "count": 2, "snippet": "."}}
  ]
}
```

It re-reads the notifications from the source file (to get canonical user content), constructs the two records with correct tool schemas and system prompts, and appends them.

### Normalization

`normalize_dataset.py` is an idempotent fix-up tool. It rewrites every record to ensure:

- `assistant.content` is exactly `""`
- System prompts match the canonical versions verbatim
- User content format is consistent
- Tools list matches the record shape
- JSON uses standard serialization

Run it whenever system prompts or tool definitions change upstream in the Kotlin code.

### Verification

`verify_dataset.py` checks three things:

1. **Script-vs-Kotlin alignment:** System prompts and tool definitions in the Python scripts match the Kotlin source code exactly
2. **Dataset invariants:** Every record has correct assistant content, system prompt, tools list, and user content format
3. **No drift:** The pipeline configuration matches reality

---

## Training configuration

Fine-tuning was done via Unsloth Studio on a Colab GPU instance.

### Model and dataset

- Base model: Gemma 4 E2B (2.58 GB)
- Training records: ~1,131
- Max sequence length: ~1,500 tokens (P90: ~1,179)
- Class balance: ~2:1 noise:matters

### LoRA adapter

| Parameter | Value | Rationale |
|---|---|---|
| `lora_rank` | 16 | Small enough to prevent memorization |
| `lora_alpha` | 32 | Standard 2× scaling |
| `lora_dropout` | 0.05 | Light regularization |
| `target_modules` | all-linear | Full adaptation of linear layers |

### Training hyperparameters

| Parameter | Value |
|---|---|
| Epochs | 2 |
| Batch size | 2 |
| Gradient accumulation | 4 |
| Effective batch size | 8 |
| Learning rate | 2e-4 |
| Optimizer | AdamW 8-bit |
| LR scheduler | Cosine |
| Warmup steps | 15 |
| Weight decay | 0.01 |
| Max sequence length | 2048 |
| Precision | bf16 |

### Steps

```
Samples:                 1,131
Effective batch:         2 × 4 = 8
Steps per epoch:         1,131 ÷ 8 ≈ 142
Total steps (2 epochs):  284

Warmup steps:            15        (~5% of 284)
Eval / Save every:       28 steps  (10 evaluations per run)
```

### Why this won't overfit

- **2 epochs** . enough to learn the schema, not enough to memorize 1,131 samples
- **Rank 16** . small adapter capacity, can't memorize the dataset
- **Dropout 0.05 + weight decay 0.01** . dual regularization
- **`load_best_model_at_end: true`** . keep the best checkpoint, not the final one

---

## Export to Android

After fine-tuning:

1. LoRA weights are merged into the base model
2. The merged model is exported as GGUF format
3. GGUF is converted to LiteRT-LM format for on-device inference
4. The resulting `.litertlm` file replaces the base model in the app's model directory

At inference time, the fine-tuned model runs through the same `InferenceProvider` interface as the base model. No code changes are needed. The model just performs better at the notification triage task.

---

## What to read next

- [How Focal works](index.html) . the full story from notification to widget
- [Classification & rules](classification-and-rules.html) . how the model is invoked at inference time
- [LLM inference pipeline](inference-pipeline.html) . how the model is loaded and streamed
- [Technology decisions](tech-decisions.html) . why LiteRT-LM and on-device inference
