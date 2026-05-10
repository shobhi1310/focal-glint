# Cross-App Notification Clustering — Research Findings

**Date:** April 19, 2026
**Author:** Shubhankar Bhadra + Claude (implementation partner)
**Status:** Hypothesis partially validated — embedding model produces insufficiently discriminative vectors for short notification text. Needs further research.

---

## 1. What We're Building

Focal is an on-device Android app that triages notifications into a "Daily Digest" without any cloud calls. The user's phone receives ~40-100 notifications/day from ~15-20 apps. Focal captures them, classifies them (matters vs noise), and presents the important ones as **stories** — not as a raw notification list.

The key differentiator: stories should be about **people and events**, not about apps. "Mom called twice — wants to confirm Sunday lunch" should pull from Phone (2 missed calls) + WhatsApp ("are you and Maya coming sunday?") into ONE story card. Currently, each app's notifications are siloed into separate stories.

## 2. The Problem

The v2 TopicEngine groups notifications by **app + sender** (a two-level structural grouping):
- Level 1: Group by `packageName` (all WhatsApp together, all Gmail together)
- Level 2: Within each app, sub-group by `conversation ?: title`

This produces stories like:
- "WhatsApp/Mom: Kono reply korlina" (one story)
- "Phone/Mom: 2 missed calls" (separate story)
- "Messages/JM-ICICIT-S: Rs 44,000 spent on ICICI Card" (one story)
- "Truecaller/Rs 44,000 spent on ICICI Card" (separate story — same event!)

The user sees 4 stories when they should see 2: one about Mom, one about the ICICI transaction. Cross-app merging is the missing capability.

## 3. Why On-Device Embedding

### Why not LLM-based grouping?
In v1, we tried an LLM merge pass: feed all notification summaries to the LLM and ask "which are about the same thing?" This had three problems:
1. **OOM on 6GB device** — Gemma 3 1B with 24 app groups caused out-of-memory crashes
2. **Unreliable JSON parsing** — the small LLM frequently returned malformed responses
3. **Expensive** — each grouping call consumed a significant portion of the LLM budget (max 8 calls/batch)

### Why embeddings?
Grouping is a **matching** problem (are these two texts about the same thing?), not a **creative** problem. Embeddings are designed for exactly this: map text to a vector space where semantically similar texts are close together. The hypothesis was:

- Embed each notification into a 768-dim vector using a lightweight model
- Compare new notifications against existing topic members using cosine similarity
- Assign to the most similar topic if score exceeds a threshold, else create a new topic
- Reserve the LLM exclusively for narrative generation (the creative part)

### Why nearest-member, not centroid?
At our scale (~300 vectors/day, ~30 topics × ~10 members), brute-force nearest-member comparison takes ~0.2ms. A centroid averages out to a blurry concept when a topic holds diverse content. Nearest-member handles topic diversity correctly: if a topic contains "Mom missed call" and "Mom WhatsApp about Sunday lunch," a new "Mom asked about Maya" matches the WhatsApp member strongly, even if the centroid would be indistinct.

### Device constraints
- **Device:** Xiaomi Redmi Note 10 Pro (Snapdragon 720G, 6GB RAM, Android 13)
- **LLM model:** Gemma 3 1B (584MB) or Gemma 4 E2B (2.6GB) via LiteRT-LM
- **Embedding model:** Gecko 110M (443MB float32) via AI Edge RAG SDK
- **Memory budget:** Both models loaded simultaneously = ~3GB, leaving ~3GB for OS + app + KV cache

## 4. Implementation

### Architecture
Two on-device models:
1. **Gecko 110M** (`Gecko_256_f32.tflite`, 443MB) — produces 768-dim L2-normalized embeddings via `GeckoEmbeddingModel` from `com.google.ai.edge.localagents:localagents-rag:0.1.0`
2. **Gemma 3 1B / Gemma 4 E2B** (via LiteRT-LM) — generates narrative headlines, daily briefing

### Pipeline (incremental processing)
1. **Embed:** For each new notification, build text `"{appName} — {title}: {content.take(300)}"`, call `GeckoEmbeddingModel.getEmbeddings()`, L2-normalize, store as 3072-byte BLOB in Room
2. **Assign:** For each unprocessed notification, compute dot product against every member of every active topic. Pick best topic if `score >= ASSIGN_THRESHOLD (0.60)`, else create new topic
3. **Narrative:** For topics that gained new members, regenerate headline via LLM. Generate daily briefing from all headlines.

### Storage
- Embeddings stored in `NotificationEntity.embedding` (ByteArray, 768 floats × 4 bytes = 3072 bytes)
- No centroid column — topics are defined solely by their member notification IDs
- No separate vector DB — Room SQLite is sufficient at our scale
- Custom `equals`/`hashCode` on NotificationEntity (by primary key) to handle ByteArray correctly

### Day window
Fixed 2AM-to-2AM window. `DailyResetWorker` (PeriodicWorkRequest) purges old topics and notifications at 2AM. Pull-to-refresh triggers a full rebuild (reset all processed flags, delete all topics, re-assign from scratch).

## 5. What We Observed

### Both models load and run correctly
- Gecko embedding model initializes in ~2.6s on CPU
- Gemma 3 1B initializes in ~10s on CPU
- Embedding a single notification takes ~1.7s (Gecko on CPU)
- The pipeline runs end-to-end: embed → assign → narrative → briefing

### The cosine similarity scores are too high for unrelated content

We embedded three notifications:

| Notification | Text |
|---|---|
| WhatsApp/Darahas | "WhatsApp — Darahas Kopparapu: Check the new post regarding claude mythos hope we don't get hacked" |
| Messages/AX-HDFCBK-S | "Messages — AX-HDFCBK-S: Spent Rs.553 On HDFC Bank Card 6512 At PYU*Swiggy Food On 2026-04-19" |
| Messages/JM-HDFCBK-S | "Messages — JM-HDFCBK-S: OTP is 190240 for txn of INR 553.00 at Swiggy Limi on HDFC Bank Card" |

**Expected similarities:**
- HDFC SMS vs HDFC OTP: ~0.85-0.95 (same transaction, same bank, same amount)
- Darahas WhatsApp vs HDFC SMS: ~0.15-0.30 (completely unrelated — tech post vs bank transaction)

**Actual similarities:**
- HDFC SMS vs HDFC OTP: **0.90** ✓ (correctly high)
- Darahas WhatsApp vs HDFC SMS: **0.79** ✗ (should be much lower)
- Darahas WhatsApp vs HDFC OTP: **0.81** ✗ (should be much lower)

### Consequence
With `ASSIGN_THRESHOLD = 0.60`, all three notifications were merged into ONE topic. The HDFC notifications arrived second and were assigned to the existing Darahas topic because they scored 0.81 against it. The topic headline was then regenerated by the LLM from all 3 members, producing an HDFC-focused narrative that erased the original Darahas context.

### Root cause analysis
The Gecko 110M model (`Gecko_256_f32.tflite`, float32) appears to produce **insufficiently discriminative embeddings for short notification-style text**. Possible reasons:

1. **Short text collapse:** Gecko is trained on document retrieval tasks with longer passages. Notification text is extremely short (20-80 tokens). With fewer tokens, the model has less signal to differentiate, and embeddings cluster in a narrow region of the vector space.

2. **Task type mismatch:** We use `EmbedData.TaskType.RETRIEVAL_DOCUMENT` for all embeddings. The model may behave differently with `RETRIEVAL_QUERY`, `SEMANTIC_SIMILARITY`, or `CLUSTERING` task types — these tell the model to optimize its embedding for different objectives.

3. **No domain-specific tuning:** Gecko is a general-purpose embedding model. Notification text is a specific domain with unusual characteristics — abbreviated sender names ("AX-HDFCBK-S"), mixed languages, truncated content, app-specific formatting.

4. **Quantized vs float model tradeoff:** We used the float32 variant (443MB) for best quality. The quantized variants are smaller but would be even less discriminative.

5. **Embedding dimension:** Gecko supports Matryoshka representation learning — we could potentially use only the first 128 or 256 dimensions instead of all 768, which may or may not improve discrimination on short texts.

## 6. What Remains Correct

Despite the embedding quality issue, several architectural decisions are validated:

- **Room BLOB storage for vectors** works correctly (3072 bytes/vector, ~700KB for 200 notifications)
- **Nearest-member comparison** is sub-millisecond and architecturally correct
- **Incremental processing** works (only embed new notifications, only regenerate dirty topics)
- **Day window** boundary logic is correct
- **Pipeline orchestration** (embed → assign → regenerate) is clean and testable (5 unit tests pass)
- **Dual model loading** works (Gecko + Gemma on same device)

## 7. Open Questions for Further Research

### A. Can we improve Gecko's discrimination on short text?
- Does `EmbedData.TaskType.CLUSTERING` produce better-separated vectors than `RETRIEVAL_DOCUMENT`?
- Does prefixing the text with a semantic category (e.g., "Banking transaction: ..." vs "Personal chat: ...") improve separation?
- Would EmbeddingGemma (308M, based on Gemma 3, designed for phones) perform better than Gecko 110M?
- Would the quantized Gecko variants (`Gecko_256_quant.tflite`, ~114MB) produce comparable or worse results?

### B. Should we use a hybrid approach instead?
- **Heuristic pre-grouping + embedding verification:** Extract entity signals (person names, amounts, reference numbers) heuristically, form candidate groups, then use embeddings only to verify or reject merges.
- **LLM-based grouping with Gemma 4 E2B:** With 32K context window, feed all notifications in one prompt and ask the LLM to cluster them. More expensive but semantically accurate. Feasible now that we upgraded from Gemma 3 1B (8K context).
- **Two-stage: fast embedding filter + LLM confirmation:** Use embeddings to identify candidates (lower threshold, say 0.5), then ask LLM to confirm each merge. Bounds LLM calls to the number of candidate pairs.

### C. Is the threshold the right knob to tune?
- With similarities ranging 0.79-0.90 for both related AND unrelated pairs, there may be no threshold that correctly separates them.
- If the embedding space is too compressed for this domain, no threshold will work — we need better embeddings or a different approach.

### D. Alternative embedding models
- **EmbeddingGemma** (308M, <200MB RAM quantized) — Google's newer model, explicitly designed for on-device use. May produce better-separated embeddings.
- **Universal Sentence Encoder** (USE, ~6MB) — much smaller, 100-dim output, optimized for sentence-level similarity. Available via MediaPipe with a confirmed working download URL.
- **Fine-tuned Gecko** — Gecko supports fine-tuning via Sentence Transformers. Training on a notification-specific dataset (pairs of same-topic vs different-topic notifications) could dramatically improve discrimination.

## 8. Current State of Code

All code is on branch `dev-darahas` and pushed to remote. The embedding pipeline is functional end-to-end but produces incorrect groupings due to the similarity score issue described above.

### Key files
- `VectorMath.kt` — byte conversion, L2 normalize, dot product (7 unit tests)
- `GeckoEmbeddingProvider.kt` — direct API integration with `GeckoEmbeddingModel`
- `EmbeddingProvider.kt` — interface
- `DayWindow.kt` — 2AM-2AM window boundaries (4 unit tests)
- `TopicEngine.kt` — embed → assign → regenerate pipeline (5 unit tests)
- `DailyResetWorker.kt` — 2AM scheduled purge
- `NotificationEntity.kt` — embedding + processedForTopics columns (migration v4→v5)
- `TopicEntity.kt` — needsNarrativeRegen column

### Dependencies added
- `com.google.ai.edge.localagents:localagents-rag:0.1.0`
- `com.google.mediapipe:tasks-genai:0.10.22`
