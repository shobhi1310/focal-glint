# Cross-App Story Clustering — Embedding-Based

**Goal:** Merge notifications from multiple apps into unified stories using on-device embedding similarity, reserving the LLM exclusively for narrative generation, summarization, and future action extraction.

**Core insight:** Stories are about people and events, not apps. Grouping is a *matching* problem best solved by embeddings; narrative generation is a *creative* problem best solved by an LLM. Use each tool for what it is good at.

---

## 1. Architecture

Two on-device models running in complementary roles:

- **EmbeddingGemma** (via AI Edge RAG SDK, ~200MB RAM, 768-dim output) — produces L2-normalized embedding vectors for notification text. Handles fuzzy name matching and cross-app semantic correlation implicitly. One embedding call per new notification.

- **Gemma 4 E2B** (via LiteRT-LM, ~2.6GB model, 32K context) — generates narrative headlines, daily briefing, and future action suggestions. Only called for topics whose membership changed.

Memory budget on 6GB device: ~2.8GB for both models. The models are used at different pipeline stages and do not need to hold active inference sessions simultaneously.

Pipeline:

1. **Embed** — For each unprocessed notification, produce a 768-dim L2-normalized vector via EmbeddingGemma. Persist to Room.
2. **Assign** — Cosine similarity (dot product on normalized vectors) nearest-member matching. Compare the new vector against every member of every active topic. Pick the topic with the highest score; if below threshold, create a new topic.
3. **Regenerate narratives** — For each topic marked dirty by step 2, regenerate its headline via Gemma 4 E2B. Generate the daily briefing from all current headlines.

Processing is **incremental** — embeddings persist, so each worker run only embeds new notifications and only asks the LLM about topics whose membership changed.

**Pull-to-refresh = full rebuild** — Reset processed flags, delete all topics (keep notifications and their embeddings), re-assign all notifications from scratch, regenerate all narratives.

## 2. Daily Window

**Fixed 2 AM to 2 AM window.**

- Scheduled WorkManager periodic job fires at 2 AM daily
- Purges topics from the previous day
- Deletes notifications outside the new day window (including their embeddings)
- Resets `processedForTopics` flags on any surviving notifications

## 3. Assignment Algorithm

Centroid-free nearest-member cosine similarity over all members of all active topics. No vector database, no ANN index — at our scale (few hundred vectors/day, max ~30 topics × ~10 members each) brute force is sub-millisecond in Kotlin.

```kotlin
suspend fun assignOrCreateTopic(
    notif: NotificationEntity,
    vec: FloatArray  // L2-normalized
): String {
    val activeTopics = topicRepo.getActiveTopicsInWindow(dayStart, dayEnd)
    if (activeTopics.isEmpty()) return createTopic(notif, vec)

    var bestTopicId: String? = null
    var bestScore = Float.NEGATIVE_INFINITY

    for (topic in activeTopics) {
        val memberIds = JSONArray(topic.notificationIds).asStringList()
        val members = notifRepo.getByIds(memberIds)
        var topicBest = Float.NEGATIVE_INFINITY
        for (m in members) {
            val mv = m.embedding?.toFloats() ?: continue
            val s = VectorMath.dot(vec, mv)
            if (s > topicBest) topicBest = s
        }
        if (topicBest > bestScore) {
            bestScore = topicBest
            bestTopicId = topic.id
        }
    }

    return if (bestScore >= ASSIGN_THRESHOLD && bestTopicId != null) {
        appendToTopic(bestTopicId, notif.id)
        markTopicDirty(bestTopicId)
        bestTopicId
    } else {
        createTopic(notif, vec)
    }
}

private const val ASSIGN_THRESHOLD = 0.60f
```

### Why nearest-member, not centroid

Centroids average out to a blurry concept when a topic holds diverse but related content (Mom's missed call + Mom's message about Sunday lunch + Mom asking about Maya). A new "Mom WhatsApp about recipe" may match one member strongly but fall below the centroid threshold. Nearest-member correctly handles topic diversity.

At our scale, nearest-member over all members is ~200µs per assignment. A centroid adds state to maintain and breaks on diverse topics for no measurable speedup. We do not use centroids.

### Threshold tuning

Start at `0.60`. Log every assignment's score during development for a few days. Tune in `0.02` increments based on false-merge vs false-split patterns. Later, expose as a "Story sensitivity" slider in Settings.

## 4. Embedding Model Integration

Uses Google's AI Edge RAG SDK to load EmbeddingGemma:

```gradle
implementation("com.google.ai.edge.localagents:localagents-rag:0.1.0")
```

EmbeddingGemma is instruction-tuned. We use a **single consistent prompt template** for all notification embeddings so vectors are comparable:

```
task: sentence similarity | text: {appName} — {title}: {content}
```

Inputs over 2K tokens are truncated by the model. Notification text is always much shorter than this limit.

**Blank-content guard:** If both `title` and `content` are empty, skip embedding (embedding empty strings produces degenerate vectors that falsely match everything).

**Stale-embedding guard:** When `upsertNotification` updates an existing row's content (same `notificationKey`, new text), invalidate the embedding (`embedding = null`, `embeddedAt = null`) so the next worker run re-embeds.

### EmbeddingProvider interface

```kotlin
interface EmbeddingProvider {
    suspend fun initialize(modelPath: String, useGpu: Boolean = true)
    suspend fun embed(text: String): FloatArray  // returns L2-normalized 768-dim vector
    fun isReady(): Boolean
    fun close()
}
```

## 5. Database Changes

### NotificationEntity

Add three columns (migration v4→v5):

```kotlin
@ColumnInfo(name = "embedding")
val embedding: ByteArray? = null,  // 768 L2-normalized floats → 3072 bytes

@ColumnInfo(name = "embedded_at")
val embeddedAt: Long? = null,

@ColumnInfo(name = "processed_for_topics")
val processedForTopics: Boolean = false
```

Room requires custom `equals`/`hashCode` for entities containing `ByteArray` — Kotlin data classes use reference equality for arrays by default. Implement explicitly:

```kotlin
override fun equals(other: Any?): Boolean { ... }
override fun hashCode(): Int { ... }
```

### TopicEntity

Add dirty-tracking flag:

```kotlin
@ColumnInfo(name = "needs_narrative_regen")
val needsNarrativeRegen: Boolean = true
```

No centroid column.

### Migration v4→v5

```sql
ALTER TABLE notifications ADD COLUMN embedding BLOB;
ALTER TABLE notifications ADD COLUMN embedded_at INTEGER;
ALTER TABLE notifications ADD COLUMN processed_for_topics INTEGER NOT NULL DEFAULT 0;
ALTER TABLE topics ADD COLUMN needs_narrative_regen INTEGER NOT NULL DEFAULT 1;
```

### New DAO queries

```kotlin
// NotificationDao
@Query("SELECT * FROM notifications WHERE processed_for_topics = 0 AND posted_at >= :since AND posted_at < :until AND is_summary = 0 AND category = 'matters'")
suspend fun getUnprocessedMatters(since: Long, until: Long): List<NotificationEntity>

@Query("SELECT * FROM notifications WHERE embedding IS NULL AND posted_at >= :since AND posted_at < :until AND is_summary = 0 AND category = 'matters'")
suspend fun getUnembedded(since: Long, until: Long): List<NotificationEntity>

@Query("UPDATE notifications SET embedding = :embedding, embedded_at = :timestamp WHERE id = :id")
suspend fun setEmbedding(id: String, embedding: ByteArray, timestamp: Long)

@Query("UPDATE notifications SET processed_for_topics = 1 WHERE id IN (:ids)")
suspend fun markProcessedForTopics(ids: List<String>)

@Query("UPDATE notifications SET processed_for_topics = 0")
suspend fun resetAllProcessedFlags()

@Query("UPDATE notifications SET embedding = NULL, embedded_at = NULL WHERE id = :id")
suspend fun invalidateEmbedding(id: String)

@Query("DELETE FROM notifications WHERE posted_at < :before")
suspend fun deleteOlderThan(before: Long)

// TopicDao
@Query("SELECT * FROM topics WHERE updated_at >= :since AND updated_at < :until AND headline != 'BRIEFING'")
suspend fun getActiveTopicsInWindow(since: Long, until: Long): List<TopicEntity>

@Query("UPDATE topics SET needs_narrative_regen = 1, updated_at = :timestamp WHERE id = :id")
suspend fun markDirty(id: String, timestamp: Long)

@Query("UPDATE topics SET needs_narrative_regen = 0 WHERE id = :id")
suspend fun markClean(id: String)
```

## 6. Vector Math Helpers

```kotlin
object VectorMath {
    fun FloatArray.toBytes(): ByteArray =
        ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).also { bb ->
            forEach(bb::putFloat)
        }.array()

    fun ByteArray.toFloats(): FloatArray {
        val bb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { bb.float }
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var ss = 0f
        for (x in v) ss += x * x
        val inv = 1f / sqrt(ss).coerceAtLeast(1e-8f)
        return FloatArray(v.size) { v[it] * inv }
    }

    /** Assumes both inputs L2-normalized → dot product equals cosine similarity. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
```

## 7. TopicEngine Pipeline

### Automatic worker (incremental, triggered by ClassificationWorker)

1. Compute day window `(start, end)` — 2 AM today to 2 AM tomorrow (or yesterday's window if before 2 AM).
2. `getUnembedded(start, end)` — embed each, persist vector + timestamp.
3. `getUnprocessedMatters(start, end)` — for each, run `assignOrCreateTopic(notif, vec)`.
4. `markProcessedForTopics(processed_ids)`.
5. Load topics with `needs_narrative_regen = 1` in the current window. For each, fetch all member notifications and regenerate headline via `PromptBuilder.buildNarrativePrompt(members)` + Gemma 4 E2B. `markClean(topicId)`.
6. If any topic was regenerated: regenerate daily briefing from all active topic headlines, upsert BRIEFING topic.

### Full rebuild (pull-to-refresh)

1. `resetAllProcessedFlags()`
2. Delete all topics (keep notifications and embeddings)
3. Run the incremental pipeline — all matters notifications are now unprocessed
4. All embeddings already exist, so no re-embedding happens
5. All topics will be created fresh and will all be dirty → all get narratives

### 2 AM daily reset job

1. Compute new day window `(start, end)`
2. Delete all topics
3. `deleteOlderThan(start)` — removes notifications outside the new window, cascading their embeddings
4. `resetAllProcessedFlags()` — clears remaining flags (should be no-op after delete, defensive)

## 8. Files to Change

| File | Change |
|------|--------|
| `EmbeddingProvider.kt` | New — interface |
| `EmbeddingGemmaProvider.kt` | New — implementation using AI Edge RAG SDK |
| `VectorMath.kt` | New — dot, l2Normalize, byte conversion helpers |
| `ModelManager.kt` | Add EmbeddingGemma variant entry (fileName, url, sizeLabel), keep existing Gemma variants |
| `TopicEngine.kt` | Major rewrite — embed → assign → regenerate pipeline |
| `NotificationEntity.kt` | Add embedding, embeddedAt, processedForTopics columns; custom equals/hashCode |
| `NotificationDao.kt` | Add unembedded/unprocessed/setEmbedding/markProcessed/resetAllProcessedFlags/invalidateEmbedding queries |
| `NotificationRepository.kt` | Corresponding methods |
| `TopicEntity.kt` | Add needsNarrativeRegen column |
| `TopicDao.kt` | Add getActiveTopicsInWindow/markDirty/markClean |
| `TopicRepository.kt` | Corresponding methods |
| `FocalDatabase.kt` | Version 5, MIGRATION_4_5 |
| `DatabaseModule.kt` | Register MIGRATION_4_5 |
| `IntelligenceModule.kt` | Provide EmbeddingProvider singleton |
| `FocalApplication.kt` | Initialize EmbeddingGemma on startup, schedule DailyResetWorker |
| `DailyResetWorker.kt` | New — PeriodicWorkRequest at 2 AM |
| `ClassificationWorker.kt` | Use day window instead of 24h rolling |
| `NotificationRepository.kt` (upsert) | On content update, invalidate embedding |
| `build.gradle.kts` | Add `com.google.ai.edge.localagents:localagents-rag:0.1.0` |

## 9. Testing Strategy

### Unit tests

1. **VectorMath** — toBytes/toFloats round-trip preserves values; l2Normalize produces unit-length vector; dot on orthogonal vectors is 0; dot on identical vectors is 1.
2. **Day window** — `getDayWindow()` at 1 AM returns yesterday's window; at 3 AM returns today's window; at 11 PM returns today's window.
3. **assignOrCreateTopic**
   - Empty topic list → creates new topic
   - Score above threshold → appends to best topic, marks dirty
   - Score below threshold → creates new topic
   - Notification with null embedding → creates new topic without crashing
4. **TopicEngine incremental flow** (mocked embedder + LLM)
   - N1–N3 arrive → 3 embeddings produced, topics created, narratives generated, all marked processed
   - N4 arrives (high similarity to topic 1) → N4 embedded, assigned to topic 1, only topic 1 marked dirty, only topic 1's narrative regenerated
5. **TopicEngine full rebuild** — all flags reset, all topics deleted, all notifications re-assigned, all topics dirty
6. **Embedding invalidation on update** — upsert existing notification with new content → embedding set to null
7. **Blank content guard** — notification with empty title+content skipped during embedding phase
8. **Grouping parser and response** — not needed (no LLM grouping)

### On-device simulation tests

A `TestNotificationInjector` helper inserts `NotificationEntity` rows directly into Room, bypassing NotificationListenerService. Scenarios:

1. **Cross-app person merge** — Phone "Mom" missed call + WhatsApp "Mom" message about Sunday → one topic, both apps as sources.
2. **Fuzzy name match** — Gmail from "Subhankar Bhadra" + Slack from "Subhankar B." → one topic (EmbeddingGemma's semantic space should map both near each other).
3. **Financial cross-app** — Messages "₹44,000 spent on ICICI Card" + Truecaller "₹44,000 ICICI Bank" → one topic.
4. **Unrelated stays unrelated** — Messages "OTP is 483921" + Messages "Your flight PNR" → two topics.
5. **Incremental** — Inject N1-N3, trigger worker, verify 2-3 topics. Inject N4 related to N1's topic, trigger worker, verify N4 appended (check `notificationIds` JSON), verify only that topic's headline changed.
6. **Pull-to-refresh** — After incremental test, trigger full rebuild, verify all processed flags reset and topics recomputed.
7. **2 AM reset** — Inject notifications with posted_at before current day window, run DailyResetWorker directly, verify topics purged and old notifications deleted.

## 10. Pitfalls

1. **Forgetting to L2-normalize at storage time** — normalize once in the embedder; never re-normalize at query time.
2. **Mixing embedding prompt templates** — pick one template and use it consistently.
3. **Re-embedding on every worker run** — embed once, persist, reuse.
4. **Empty-content embeddings** — skip, do not produce degenerate vectors.
5. **Stale embeddings after content update** — invalidate in `upsertNotification`.
6. **Forgetting custom equals/hashCode** — Room will behave incorrectly with ByteArray columns in data classes otherwise.
7. **Native model lifecycle** — EmbeddingGemma and Gemma 4 E2B both hold native resources; ensure `close()` is called on app shutdown to avoid leaks.
8. **Running both models concurrently** — serialize model usage via the existing single-thread dispatcher; do not embed and generate in parallel.
