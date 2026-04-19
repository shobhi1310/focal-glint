# Cross-App Story Clustering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace app+sender grouping with embedding-based nearest-member clustering so notifications from multiple apps merge into unified stories.

**Architecture:** EmbeddingGemma (via AI Edge RAG SDK) produces 768-dim L2-normalized vectors per notification. New notifications are assigned to the nearest existing topic by cosine similarity (brute-force nearest-member, no centroid, no vector DB). Gemma 4 E2B generates narratives only for topics whose membership changed. Fixed 2AM-2AM day window with scheduled purge.

**Tech Stack:** AI Edge RAG SDK (`localagents-rag:0.1.0`), LiteRT-LM 0.10.2, Room (migration v4→v5), Jetpack Compose, WorkManager, Kotlin coroutines

---

## Task 1: VectorMath Utility + Tests

**Files to create:**
- `app/src/main/java/com/focal/intelligence/VectorMath.kt`
- `app/src/test/java/com/focal/intelligence/VectorMathTest.kt`

- [ ] **Step 1: Write VectorMath tests**

Create `app/src/test/java/com/focal/intelligence/VectorMathTest.kt`:
```kotlin
package com.focal.intelligence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {

    @Test
    fun `toBytes and toFloats round-trip preserves values`() {
        val original = floatArrayOf(1.0f, -0.5f, 0.123f, 0f)
        val bytes = VectorMath.toBytes(original)
        val restored = VectorMath.toFloats(bytes)
        assertArrayEquals(original, restored, 1e-6f)
    }

    @Test
    fun `l2Normalize produces unit-length vector`() {
        val v = floatArrayOf(3f, 4f)
        val n = VectorMath.l2Normalize(v)
        val length = Math.sqrt((n[0] * n[0] + n[1] * n[1]).toDouble()).toFloat()
        assertEquals(1f, length, 1e-5f)
        assertEquals(0.6f, n[0], 1e-5f)
        assertEquals(0.8f, n[1], 1e-5f)
    }

    @Test
    fun `l2Normalize handles zero vector`() {
        val v = floatArrayOf(0f, 0f, 0f)
        val n = VectorMath.l2Normalize(v)
        assertEquals(3, n.size)
    }

    @Test
    fun `dot of identical normalized vectors is 1`() {
        val v = VectorMath.l2Normalize(floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, VectorMath.dot(v, v), 1e-5f)
    }

    @Test
    fun `dot of orthogonal vectors is 0`() {
        val a = VectorMath.l2Normalize(floatArrayOf(1f, 0f))
        val b = VectorMath.l2Normalize(floatArrayOf(0f, 1f))
        assertEquals(0f, VectorMath.dot(a, b), 1e-5f)
    }

    @Test
    fun `dot of opposite vectors is -1`() {
        val a = VectorMath.l2Normalize(floatArrayOf(1f, 0f))
        val b = VectorMath.l2Normalize(floatArrayOf(-1f, 0f))
        assertEquals(-1f, VectorMath.dot(a, b), 1e-5f)
    }

    @Test
    fun `toBytes produces correct byte count`() {
        val v = floatArrayOf(1f, 2f, 3f)
        val bytes = VectorMath.toBytes(v)
        assertEquals(12, bytes.size) // 3 floats × 4 bytes
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew testDebugUnitTest --tests "com.focal.intelligence.VectorMathTest"
```
Expected: compilation error — `VectorMath` not found.

- [ ] **Step 3: Implement VectorMath**

Create `app/src/main/java/com/focal/intelligence/VectorMath.kt`:
```kotlin
package com.focal.intelligence

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object VectorMath {

    fun toBytes(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { bb ->
            v.forEach(bb::putFloat)
        }.array()

    fun toFloats(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { bb.float }
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var ss = 0f
        for (x in v) ss += x * x
        val inv = 1f / sqrt(ss).coerceAtLeast(1e-8f)
        return FloatArray(v.size) { v[it] * inv }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
```

- [ ] **Step 4: Run tests — verify all pass**

```bash
./gradlew testDebugUnitTest --tests "com.focal.intelligence.VectorMathTest"
```
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/VectorMath.kt app/src/test/java/com/focal/intelligence/VectorMathTest.kt
git commit -m "feat: add VectorMath utility for embedding byte conversion and cosine similarity"
```

---

## Task 2: Database Migration + Entity Changes

**Files to modify:**
- `app/src/main/java/com/focal/data/db/entity/NotificationEntity.kt`
- `app/src/main/java/com/focal/data/db/entity/TopicEntity.kt`
- `app/src/main/java/com/focal/data/db/dao/NotificationDao.kt`
- `app/src/main/java/com/focal/data/db/dao/TopicDao.kt`
- `app/src/main/java/com/focal/data/db/FocalDatabase.kt`
- `app/src/main/java/com/focal/di/DatabaseModule.kt`
- `app/src/main/java/com/focal/data/repository/NotificationRepository.kt`
- `app/src/main/java/com/focal/data/repository/TopicRepository.kt`

- [ ] **Step 1: Add columns to NotificationEntity**

Add after `notificationKey` in `NotificationEntity.kt`:
```kotlin
    @ColumnInfo(name = "embedding")
    val embedding: ByteArray? = null,

    @ColumnInfo(name = "embedded_at")
    val embeddedAt: Long? = null,

    @ColumnInfo(name = "processed_for_topics")
    val processedForTopics: Boolean = false
```

Since `NotificationEntity` is a data class with a `ByteArray` field, Kotlin's auto-generated `equals`/`hashCode` will use reference equality for the array. Room internally uses `equals` for diff detection. Override both:

Add at the bottom of the class body (before the closing `)`):
```kotlin
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotificationEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
```

This is correct for Room — entities are identity-equal by primary key.

- [ ] **Step 2: Add needsNarrativeRegen to TopicEntity**

Add after `briefingContribution` in `TopicEntity.kt`:
```kotlin
    @ColumnInfo(name = "needs_narrative_regen")
    val needsNarrativeRegen: Boolean = true
```

- [ ] **Step 3: Add new queries to NotificationDao**

Add to `NotificationDao.kt`:
```kotlin
    @Query("SELECT * FROM notifications WHERE embedding IS NULL AND posted_at >= :since AND posted_at < :until AND is_summary = 0 AND category = 'matters'")
    suspend fun getUnembedded(since: Long, until: Long): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE processed_for_topics = 0 AND posted_at >= :since AND posted_at < :until AND is_summary = 0 AND category = 'matters'")
    suspend fun getUnprocessedMatters(since: Long, until: Long): List<NotificationEntity>

    @Query("UPDATE notifications SET embedding = :embedding, embedded_at = :timestamp WHERE id = :id")
    suspend fun setEmbedding(id: String, embedding: ByteArray, timestamp: Long)

    @Query("UPDATE notifications SET processed_for_topics = 1 WHERE id IN (:ids)")
    suspend fun markProcessedForTopics(ids: List<String>)

    @Query("UPDATE notifications SET processed_for_topics = 0")
    suspend fun resetAllProcessedFlags()

    @Query("UPDATE notifications SET embedding = NULL, embedded_at = NULL WHERE id = :id")
    suspend fun invalidateEmbedding(id: String)
```

- [ ] **Step 4: Add new queries to TopicDao**

Add to `TopicDao.kt`:
```kotlin
    @Query("SELECT * FROM topics WHERE headline != 'BRIEFING' AND updated_at >= :since AND updated_at < :until")
    suspend fun getActiveTopicsInWindow(since: Long, until: Long): List<TopicEntity>

    @Query("UPDATE topics SET needs_narrative_regen = 1, updated_at = :timestamp WHERE id = :id")
    suspend fun markDirty(id: String, timestamp: Long)

    @Query("UPDATE topics SET needs_narrative_regen = 0 WHERE id = :id")
    suspend fun markClean(id: String)
```

- [ ] **Step 5: Add MIGRATION_4_5 to FocalDatabase**

Add in the companion object:
```kotlin
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN embedding BLOB")
                db.execSQL("ALTER TABLE notifications ADD COLUMN embedded_at INTEGER")
                db.execSQL("ALTER TABLE notifications ADD COLUMN processed_for_topics INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE topics ADD COLUMN needs_narrative_regen INTEGER NOT NULL DEFAULT 1")
            }
        }
```

Bump `version = 5` in the `@Database` annotation.

- [ ] **Step 6: Register migration in DatabaseModule**

In `DatabaseModule.kt`, add `FocalDatabase.MIGRATION_4_5` to the `.addMigrations(...)` call.

- [ ] **Step 7: Add repository methods**

In `NotificationRepository.kt`, add:
```kotlin
    suspend fun getUnembedded(since: Long, until: Long): List<NotificationEntity> {
        return notificationDao.getUnembedded(since, until)
    }

    suspend fun getUnprocessedMatters(since: Long, until: Long): List<NotificationEntity> {
        return notificationDao.getUnprocessedMatters(since, until)
    }

    suspend fun setEmbedding(id: String, embedding: ByteArray) {
        notificationDao.setEmbedding(id, embedding, System.currentTimeMillis())
    }

    suspend fun markProcessedForTopics(ids: List<String>) {
        notificationDao.markProcessedForTopics(ids)
    }

    suspend fun resetAllProcessedFlags() {
        notificationDao.resetAllProcessedFlags()
    }

    suspend fun invalidateEmbedding(id: String) {
        notificationDao.invalidateEmbedding(id)
    }
```

In `TopicRepository.kt`, add:
```kotlin
    suspend fun getActiveTopicsInWindow(since: Long, until: Long): List<TopicEntity> {
        return topicDao.getActiveTopicsInWindow(since, until)
    }

    suspend fun markDirty(topicId: String) {
        topicDao.markDirty(topicId, System.currentTimeMillis())
    }

    suspend fun markClean(topicId: String) {
        topicDao.markClean(topicId)
    }
```

- [ ] **Step 8: Invalidate embedding on content update**

In `NotificationRepository.upsertNotification()`, when updating an existing notification's content, also clear the embedding. Update the existing `if (existing != null)` block — add after the `notificationDao.update(...)` call:

```kotlin
            // Invalidate embedding if content changed
            if (existing.title != notification.title || existing.content != notification.content || existing.bigText != notification.bigText) {
                notificationDao.invalidateEmbedding(existing.id)
            }
```

- [ ] **Step 9: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: add embedding + topic tracking columns with migration v4→v5"
```

---

## Task 3: EmbeddingProvider + Day Window Helper

**Files to create:**
- `app/src/main/java/com/focal/intelligence/EmbeddingProvider.kt`
- `app/src/main/java/com/focal/intelligence/GeckoEmbeddingProvider.kt`
- `app/src/main/java/com/focal/intelligence/DayWindow.kt`
- `app/src/test/java/com/focal/intelligence/DayWindowTest.kt`

**Files to modify:**
- `app/build.gradle.kts`
- `app/src/main/java/com/focal/di/IntelligenceModule.kt`
- `app/src/main/java/com/focal/intelligence/ModelManager.kt`

- [ ] **Step 1: Add AI Edge RAG SDK dependency**

In `app/build.gradle.kts`, add after the LiteRT-LM line:
```kotlin
    // AI Edge RAG SDK for on-device embeddings
    implementation("com.google.ai.edge.localagents:localagents-rag:0.1.0")
    implementation("com.google.mediapipe:tasks-genai:0.10.22")
```

- [ ] **Step 2: Add embedding model to ModelManager**

In `ModelManager.kt`, add a new `ModelVariant` entry is NOT needed — the embedding model uses a `.tflite` file, not `.litertlm`. Instead add dedicated embedding model fields:

Add to `ModelManager` class:
```kotlin
    val embeddingModelDir: File
        get() = File(modelDir, "embeddings")

    val geckoModelFile: File
        get() = File(embeddingModelDir, GECKO_MODEL_FILENAME)

    val geckoTokenizerFile: File
        get() = File(embeddingModelDir, GECKO_TOKENIZER_FILENAME)

    val isEmbeddingModelAvailable: Boolean
        get() = geckoModelFile.exists() && geckoModelFile.length() > 1_000_000L &&
                geckoTokenizerFile.exists()

    fun ensureEmbeddingModelDir() {
        if (!embeddingModelDir.exists()) embeddingModelDir.mkdirs()
    }
```

Add to companion object:
```kotlin
        const val GECKO_MODEL_FILENAME = "Gecko_256_f32.tflite"
        const val GECKO_TOKENIZER_FILENAME = "sentencepiece.model"
```

- [ ] **Step 3: Create EmbeddingProvider interface**

Create `app/src/main/java/com/focal/intelligence/EmbeddingProvider.kt`:
```kotlin
package com.focal.intelligence

interface EmbeddingProvider {
    suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean = true)
    suspend fun embed(text: String): FloatArray
    fun isReady(): Boolean
    fun close()
}
```

- [ ] **Step 4: Create GeckoEmbeddingProvider implementation**

Create `app/src/main/java/com/focal/intelligence/GeckoEmbeddingProvider.kt`:
```kotlin
package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.localagents.rag.memory.embedding.GeckoEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Optional

class GeckoEmbeddingProvider : EmbeddingProvider {

    private var embedder: GeckoEmbeddingModel? = null

    override suspend fun initialize(modelPath: String, tokenizerPath: String, useGpu: Boolean) {
        withContext(Dispatchers.IO) {
            embedder = GeckoEmbeddingModel(
                modelPath,
                Optional.of(tokenizerPath),
                useGpu
            )
            Log.d(TAG, "Gecko embedding model initialized (gpu=$useGpu)")
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val model = embedder
            ?: throw IllegalStateException("Embedding model not initialized")
        return withContext(Dispatchers.IO) {
            val result = model.embed(text)
            VectorMath.l2Normalize(result)
        }
    }

    override fun isReady(): Boolean = embedder != null

    override fun close() {
        embedder = null
    }

    companion object {
        private const val TAG = "GeckoEmbedding"
    }
}
```

Note: The `GeckoEmbeddingModel.embed()` API returns a `float[]`. We L2-normalize before returning. If the RAG SDK's `embed()` method signature differs (e.g., returns `List<Float>` or an `Embedding` wrapper), adapt accordingly — the key contract is: input text → output L2-normalized FloatArray.

- [ ] **Step 5: Write DayWindow tests**

Create `app/src/test/java/com/focal/intelligence/DayWindowTest.kt`:
```kotlin
package com.focal.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayWindowTest {

    @Test
    fun `at 3 AM returns today's window starting at 2 AM`() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 0)
        }
        val (start, end) = DayWindow.getWindow(cal.timeInMillis)

        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, startCal.get(Calendar.MINUTE))

        assertEquals(24 * 60 * 60 * 1000L, end - start)
    }

    @Test
    fun `at 1 AM returns yesterday's window`() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 30)
        }
        val (start, _) = DayWindow.getWindow(cal.timeInMillis)
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2, startCal.get(Calendar.HOUR_OF_DAY))

        val nowCal = Calendar.getInstance().apply { timeInMillis = cal.timeInMillis }
        assertTrue(startCal.get(Calendar.DAY_OF_YEAR) < nowCal.get(Calendar.DAY_OF_YEAR) ||
            startCal.get(Calendar.YEAR) < nowCal.get(Calendar.YEAR))
    }

    @Test
    fun `at 11 PM returns today's window`() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
        }
        val (start, _) = DayWindow.getWindow(cal.timeInMillis)
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(cal.get(Calendar.DAY_OF_YEAR), startCal.get(Calendar.DAY_OF_YEAR))
    }

    @Test
    fun `window is exactly 24 hours`() {
        val (start, end) = DayWindow.getWindow()
        assertEquals(24 * 60 * 60 * 1000L, end - start)
    }
}
```

- [ ] **Step 6: Implement DayWindow**

Create `app/src/main/java/com/focal/intelligence/DayWindow.kt`:
```kotlin
package com.focal.intelligence

import java.util.Calendar

object DayWindow {

    private const val RESET_HOUR = 2
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun getWindow(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        if (cal.get(Calendar.HOUR_OF_DAY) < RESET_HOUR) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, RESET_HOUR)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return start to (start + DAY_MS)
    }
}
```

- [ ] **Step 7: Provide EmbeddingProvider in DI**

In `IntelligenceModule.kt`, add:
```kotlin
    @Provides
    @Singleton
    fun provideEmbeddingProvider(): EmbeddingProvider {
        return GeckoEmbeddingProvider()
    }
```

Add imports for `EmbeddingProvider` and `GeckoEmbeddingProvider`.

- [ ] **Step 8: Initialize embedding model on startup**

In `FocalApplication.kt`, in the `initializeLlmIfModelExists()` method (or a new companion method), add embedding model initialization:

After the LLM initialization block, add:
```kotlin
        val modelManager = ModelManager(this)
        if (modelManager.isEmbeddingModelAvailable) {
            applicationScope.launch {
                try {
                    embeddingProvider.initialize(
                        modelManager.geckoModelFile.absolutePath,
                        modelManager.geckoTokenizerFile.absolutePath,
                        modelManager.getBackendPreference()
                    )
                    Log.d(TAG, "Embedding model initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize embedding model", e)
                }
            }
        } else {
            Log.d(TAG, "Embedding model not found. Push via: adb push ${ModelManager.GECKO_MODEL_FILENAME} ${modelManager.embeddingModelDir}")
        }
```

Add `@Inject lateinit var embeddingProvider: EmbeddingProvider` to FocalApplication.

- [ ] **Step 9: Run tests and build**

```bash
./gradlew testDebugUnitTest --tests "com.focal.intelligence.DayWindowTest"
./gradlew assembleDebug
```

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: add EmbeddingProvider with Gecko model, DayWindow helper, and RAG SDK dependency"
```

---

## Task 4: TopicEngine Rewrite — Embedding-Based Pipeline

**Files to modify:**
- `app/src/main/java/com/focal/intelligence/TopicEngine.kt`

- [ ] **Step 1: Rewrite TopicEngine with embedding-based pipeline**

Replace the entire `TopicEngine.kt`:
```kotlin
package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import org.json.JSONArray

class TopicEngine(
    private val inferenceProvider: InferenceProvider,
    private val embeddingProvider: EmbeddingProvider,
    private val notificationRepository: NotificationRepository,
    private val topicRepository: TopicRepository
) {
    companion object {
        private const val TAG = "TopicEngine"
        private const val MAX_LLM_CALLS = 8
        private const val ASSIGN_THRESHOLD = 0.60f
    }

    suspend fun generateTopics(fullRebuild: Boolean = false) {
        try {
            val (dayStart, dayEnd) = DayWindow.getWindow()

            if (fullRebuild) {
                Log.d(TAG, "Full rebuild requested")
                notificationRepository.resetAllProcessedFlags()
                topicRepository.clearAndSaveTopics(emptyList())
            }

            // Phase 1: Embed unembedded notifications
            if (embeddingProvider.isReady()) {
                val unembedded = notificationRepository.getUnembedded(dayStart, dayEnd)
                if (unembedded.isNotEmpty()) {
                    Log.d(TAG, "Embedding ${unembedded.size} notifications")
                    for (notif in unembedded) {
                        try {
                            val text = buildEmbeddingText(notif)
                            if (text.isBlank()) continue
                            val vec = embeddingProvider.embed(text)
                            notificationRepository.setEmbedding(notif.id, VectorMath.toBytes(vec))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to embed ${notif.id}", e)
                        }
                    }
                }
            }

            // Phase 2: Assign unprocessed notifications to topics
            val unprocessed = notificationRepository.getUnprocessedMatters(dayStart, dayEnd)
            if (unprocessed.isEmpty()) {
                Log.d(TAG, "No unprocessed notifications")
                return
            }

            Log.d(TAG, "Assigning ${unprocessed.size} notifications to topics")
            val processedIds = mutableListOf<String>()

            for (notif in unprocessed) {
                try {
                    val vec = notif.embedding?.let { VectorMath.toFloats(it) }
                    if (vec == null) {
                        processedIds.add(notif.id)
                        continue
                    }
                    assignOrCreateTopic(notif, vec, dayStart, dayEnd)
                    processedIds.add(notif.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to assign ${notif.id}", e)
                }
            }

            if (processedIds.isNotEmpty()) {
                notificationRepository.markProcessedForTopics(processedIds)
            }

            // Phase 3: Regenerate narratives for dirty topics
            regenerateNarratives(dayStart, dayEnd)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate topics", e)
        }
    }

    private suspend fun assignOrCreateTopic(
        notif: NotificationEntity,
        vec: FloatArray,
        dayStart: Long,
        dayEnd: Long
    ) {
        val activeTopics = topicRepository.getActiveTopicsInWindow(dayStart, dayEnd)

        if (activeTopics.isEmpty()) {
            createNewTopic(notif)
            return
        }

        var bestTopicId: String? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (topic in activeTopics) {
            val memberIds = parseJsonArray(topic.notificationIds)
            val members = notificationRepository.getByIds(memberIds)

            var topicBest = Float.NEGATIVE_INFINITY
            for (m in members) {
                val mv = m.embedding?.let { VectorMath.toFloats(it) } ?: continue
                val s = VectorMath.dot(vec, mv)
                if (s > topicBest) topicBest = s
            }

            if (topicBest > bestScore) {
                bestScore = topicBest
                bestTopicId = topic.id
            }
        }

        if (bestScore >= ASSIGN_THRESHOLD && bestTopicId != null) {
            appendToTopic(bestTopicId, notif)
            topicRepository.markDirty(bestTopicId)
            Log.d(TAG, "Assigned ${notif.appName}/${notif.title} to topic $bestTopicId (score=$bestScore)")
        } else {
            createNewTopic(notif)
            Log.d(TAG, "Created new topic for ${notif.appName}/${notif.title} (bestScore=$bestScore)")
        }
    }

    private suspend fun createNewTopic(notif: NotificationEntity) {
        val headline = "${notif.title}: ${notif.content.take(60)}"
        val sourceApps = JSONArray(listOf(notif.appName)).toString()
        val notificationIds = JSONArray(listOf(notif.id)).toString()

        val appType = DetailTemplates.detectAppType(notif.appName, notif.packageName)
        val (detailJson, actionLabel) = DetailTemplates.tryExtractDetail(listOf(notif), appType)

        val topic = TopicEntity(
            headline = headline,
            summary = (notif.bigText ?: notif.content).take(500),
            category = ClassificationResult.MATTERS,
            notificationIds = notificationIds,
            sourceApps = sourceApps,
            channelCount = 1,
            detailJson = detailJson,
            actionLabel = actionLabel,
            actionPackage = notif.packageName,
            needsNarrativeRegen = true
        )
        topicRepository.saveTopic(topic)
    }

    private suspend fun appendToTopic(topicId: String, notif: NotificationEntity) {
        val topic = topicRepository.getById(topicId) ?: return
        val existingIds = parseJsonArray(topic.notificationIds).toMutableList()
        existingIds.add(notif.id)

        val existingApps = parseJsonArray(topic.sourceApps).toMutableSet()
        existingApps.add(notif.appName)

        topicRepository.updateTopicMembers(
            topicId = topicId,
            notificationIds = JSONArray(existingIds).toString(),
            sourceApps = JSONArray(existingApps.toList()).toString(),
            channelCount = existingApps.size
        )
    }

    private suspend fun regenerateNarratives(dayStart: Long, dayEnd: Long) {
        val dirtyTopics = topicRepository.getActiveTopicsInWindow(dayStart, dayEnd)
            .filter { it.needsNarrativeRegen }

        if (dirtyTopics.isEmpty()) return

        var llmCallCount = 0
        val allNarratives = mutableListOf<String>()
        val noiseCount = notificationRepository.getRecentNotificationsSnapshot()
            .count { !it.isSummary && it.category == ClassificationResult.NOISE }

        for (topic in dirtyTopics) {
            val memberIds = parseJsonArray(topic.notificationIds)
            val members = notificationRepository.getByIds(memberIds)

            val headline = if (members.size == 1 || !inferenceProvider.isReady() || llmCallCount >= MAX_LLM_CALLS) {
                if (members.size == 1) {
                    val m = members.first()
                    "${m.title}: ${m.content.take(60)}"
                } else {
                    val apps = parseJsonArray(topic.sourceApps)
                    "${apps.firstOrNull() ?: "App"} · ${members.size} messages"
                }
            } else {
                try {
                    val prompt = PromptBuilder.buildNarrativePrompt(members)
                    val raw = inferenceProvider.generate(prompt, maxTokens = 128)
                    llmCallCount++
                    LlmResponseParser.parseNarrative(raw)
                        ?: "${members.first().appName} · ${members.size} messages"
                } catch (e: Exception) {
                    Log.w(TAG, "Narrative generation failed for topic ${topic.id}", e)
                    "${members.first().appName} · ${members.size} messages"
                }
            }

            val isLlmGenerated = llmCallCount > 0 || members.size == 1
            topicRepository.updateTopicHeadline(
                topicId = topic.id,
                headline = headline,
                summary = members.joinToString(". ") { (it.bigText ?: it.content).take(100) }.take(500),
                briefingContribution = if (isLlmGenerated) headline else null
            )
            topicRepository.markClean(topic.id)

            if (isLlmGenerated) allNarratives.add(headline)
        }

        // Also collect narratives from non-dirty topics for the briefing
        val cleanTopics = topicRepository.getActiveTopicsInWindow(dayStart, dayEnd)
            .filter { !it.needsNarrativeRegen }
        cleanTopics.forEach { it.briefingContribution?.let { bc -> allNarratives.add(bc) } }

        // Generate briefing
        if (inferenceProvider.isReady() && allNarratives.isNotEmpty() && llmCallCount < MAX_LLM_CALLS) {
            try {
                val briefingPrompt = PromptBuilder.buildBriefingPrompt(allNarratives, noiseCount)
                val briefingRaw = inferenceProvider.generate(briefingPrompt, maxTokens = 256)
                val briefingText = briefingRaw.trim().ifBlank { null }

                if (briefingText != null) {
                    val existingBriefing = topicRepository.getBriefingSnapshot(dayStart)
                    if (existingBriefing != null) {
                        topicRepository.updateTopicHeadline(
                            topicId = existingBriefing.id,
                            headline = "BRIEFING",
                            summary = briefingText.take(500),
                            briefingContribution = null
                        )
                    } else {
                        topicRepository.saveTopic(TopicEntity(
                            headline = "BRIEFING",
                            summary = briefingText.take(500),
                            category = ClassificationResult.MATTERS,
                            notificationIds = "[]",
                            sourceApps = "[]"
                        ))
                    }
                    Log.d(TAG, "Generated daily briefing")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to generate briefing", e)
            }
        }
    }

    private fun buildEmbeddingText(notif: NotificationEntity): String {
        val content = notif.bigText ?: notif.content
        if (notif.title.isBlank() && content.isBlank()) return ""
        return "${notif.appName} — ${notif.title}: ${content.take(300)}"
    }

    private fun parseJsonArray(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }
}
```

- [ ] **Step 2: Add missing TopicRepository methods**

The rewritten TopicEngine uses several new TopicRepository methods. Add to `TopicRepository.kt`:
```kotlin
    suspend fun saveTopic(topic: TopicEntity) {
        topicDao.insert(topic)
    }

    suspend fun updateTopicMembers(topicId: String, notificationIds: String, sourceApps: String, channelCount: Int) {
        val topic = topicDao.getById(topicId) ?: return
        topicDao.update(topic.copy(
            notificationIds = notificationIds,
            sourceApps = sourceApps,
            channelCount = channelCount,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun updateTopicHeadline(topicId: String, headline: String, summary: String, briefingContribution: String?) {
        val topic = topicDao.getById(topicId) ?: return
        topicDao.update(topic.copy(
            headline = headline,
            summary = summary,
            briefingContribution = briefingContribution,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun getBriefingSnapshot(since: Long): TopicEntity? {
        val twentyFourHoursMs = 24 * 60 * 60 * 1000L
        return topicDao.getActiveTopicsInWindow(since, since + twentyFourHoursMs)
            .plus(listOfNotNull(topicDao.getById("BRIEFING"))) // fallback
            .firstOrNull { it.headline == "BRIEFING" }
    }
```

Actually, the briefing query is simpler. Use the existing `getBriefing` from TopicDao. Replace `getBriefingSnapshot` with:
```kotlin
    suspend fun getBriefingInWindow(since: Long, until: Long): TopicEntity? {
        return topicDao.getById("BRIEFING") // won't work — BRIEFING has random UUID id
    }
```

Better approach — add a DAO query:
```kotlin
    // In TopicDao:
    @Query("SELECT * FROM topics WHERE headline = 'BRIEFING' AND updated_at >= :since AND updated_at < :until LIMIT 1")
    suspend fun getBriefingInWindow(since: Long, until: Long): TopicEntity?
```

And in TopicRepository:
```kotlin
    suspend fun getBriefingInWindow(since: Long, until: Long): TopicEntity? {
        return topicDao.getBriefingInWindow(since, until)
    }
```

Update TopicEngine's `regenerateNarratives` to use `topicRepository.getBriefingInWindow(dayStart, dayEnd)` instead of `getBriefingSnapshot(dayStart)`.

- [ ] **Step 3: Update IntelligenceModule to inject EmbeddingProvider into TopicEngine**

In `IntelligenceModule.kt`, update `provideTopicEngine`:
```kotlin
    @Provides
    @Singleton
    fun provideTopicEngine(
        inferenceProvider: InferenceProvider,
        embeddingProvider: EmbeddingProvider,
        notificationRepository: NotificationRepository,
        topicRepository: TopicRepository
    ): TopicEngine {
        return TopicEngine(inferenceProvider, embeddingProvider, notificationRepository, topicRepository)
    }
```

- [ ] **Step 4: Update ClassificationWorker to pass fullRebuild flag**

In `ClassificationWorker.kt`, change the `topicEngine.generateTopics()` call:
```kotlin
            topicEngine.generateTopics(fullRebuild = false)
```

- [ ] **Step 5: Update DigestViewModel.onRefresh to trigger full rebuild**

The current `onRefresh()` enqueues the worker. To signal full rebuild, we can use WorkManager input data. Alternatively, simpler: add a `fullRebuild` flag to TopicEngine that DigestViewModel sets before enqueuing.

Simplest approach: add a companion object flag to TopicEngine:
```kotlin
    companion object {
        // ... existing constants
        @Volatile
        var pendingFullRebuild = false
    }
```

In `generateTopics`:
```kotlin
    suspend fun generateTopics(fullRebuild: Boolean = pendingFullRebuild.also { pendingFullRebuild = false }) {
```

In DigestViewModel's `onRefresh()`, before enqueueing the worker:
```kotlin
            TopicEngine.pendingFullRebuild = true
```

Add import `import com.focal.intelligence.TopicEngine` in DigestViewModel.

- [ ] **Step 6: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: rewrite TopicEngine with embedding-based nearest-member clustering"
```

---

## Task 5: DailyResetWorker

**Files to create:**
- `app/src/main/java/com/focal/worker/DailyResetWorker.kt`

**Files to modify:**
- `app/src/main/java/com/focal/FocalApplication.kt`

- [ ] **Step 1: Create DailyResetWorker**

Create `app/src/main/java/com/focal/worker/DailyResetWorker.kt`:
```kotlin
package com.focal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import com.focal.intelligence.DayWindow
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DailyResetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationRepository: NotificationRepository,
    private val topicRepository: TopicRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Daily reset starting")
        val (dayStart, _) = DayWindow.getWindow()

        // Purge old notifications (before current day window)
        notificationRepository.purgeOlderThan(dayStart)

        // Delete all topics (fresh day, fresh digest)
        topicRepository.clearAndSaveTopics(emptyList())

        // Reset processed flags
        notificationRepository.resetAllProcessedFlags()

        Log.d(TAG, "Daily reset complete — new window starts at $dayStart")
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "focal_daily_reset"
        private const val TAG = "DailyResetWorker"
    }
}
```

- [ ] **Step 2: Add purgeOlderThan to NotificationRepository**

In `NotificationRepository.kt`, add (if not already present — the existing `purgeOld` uses a rolling 24h, we need a specific cutoff):
```kotlin
    suspend fun purgeOlderThan(before: Long) {
        notificationDao.deleteOlderThan(before)
    }
```

- [ ] **Step 3: Schedule DailyResetWorker on app startup**

In `FocalApplication.onCreate()`, add after `initializeLlmIfModelExists()`:
```kotlin
        scheduleDailyReset()
```

Add the method:
```kotlin
    private fun scheduleDailyReset() {
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelay = target.timeInMillis - now.timeInMillis

        val request = androidx.work.PeriodicWorkRequestBuilder<com.focal.worker.DailyResetWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            com.focal.worker.DailyResetWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
```

- [ ] **Step 4: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add DailyResetWorker for 2 AM topic purge and day window reset"
```

---

## Task 6: Unit Tests for TopicEngine

**Files to create:**
- `app/src/test/java/com/focal/intelligence/TopicEngineTest.kt`

- [ ] **Step 1: Write TopicEngine unit tests**

Create `app/src/test/java/com/focal/intelligence/TopicEngineTest.kt`:
```kotlin
package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TopicEngineTest {

    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var embeddingProvider: EmbeddingProvider
    private lateinit var notificationRepo: NotificationRepository
    private lateinit var topicRepo: TopicRepository
    private lateinit var engine: TopicEngine

    @Before
    fun setup() {
        inferenceProvider = mockk(relaxed = true)
        embeddingProvider = mockk(relaxed = true)
        notificationRepo = mockk(relaxed = true)
        topicRepo = mockk(relaxed = true)
        engine = TopicEngine(inferenceProvider, embeddingProvider, notificationRepo, topicRepo)
    }

    private fun notification(
        id: String = "n1",
        title: String = "Mom",
        content: String = "Are you coming for dinner?",
        appName: String = "WhatsApp",
        packageName: String = "com.whatsapp",
        embedding: ByteArray? = null
    ) = NotificationEntity(
        id = id,
        packageName = packageName,
        appName = appName,
        title = title,
        content = content,
        postedAt = System.currentTimeMillis(),
        category = ClassificationResult.MATTERS,
        embedding = embedding
    )

    private fun makeVec(vararg values: Float): FloatArray = VectorMath.l2Normalize(floatArrayOf(*values))
    private fun vecBytes(vararg values: Float): ByteArray = VectorMath.toBytes(makeVec(*values))

    @Test
    fun `creates new topic when no topics exist`() = runTest {
        val vec = makeVec(1f, 0f, 0f)
        val notif = notification(embedding = VectorMath.toBytes(vec))

        coEvery { embeddingProvider.isReady() } returns false
        coEvery { notificationRepo.getUnembedded(any(), any()) } returns emptyList()
        coEvery { notificationRepo.getUnprocessedMatters(any(), any()) } returns listOf(notif)
        coEvery { topicRepo.getActiveTopicsInWindow(any(), any()) } returns emptyList()
        coEvery { notificationRepo.getRecentNotificationsSnapshot() } returns emptyList()

        engine.generateTopics()

        coVerify { topicRepo.saveTopic(any()) }
        coVerify { notificationRepo.markProcessedForTopics(listOf("n1")) }
    }

    @Test
    fun `assigns notification to existing topic when similar`() = runTest {
        val existingVec = makeVec(1f, 0f, 0f)
        val newVec = makeVec(0.95f, 0.05f, 0f) // very similar

        val existingNotif = notification(id = "n1", embedding = VectorMath.toBytes(existingVec))
        val newNotif = notification(id = "n2", embedding = VectorMath.toBytes(newVec))

        val existingTopic = TopicEntity(
            id = "t1",
            headline = "Mom: Are you coming?",
            summary = "test",
            category = ClassificationResult.MATTERS,
            notificationIds = JSONArray(listOf("n1")).toString(),
            sourceApps = JSONArray(listOf("WhatsApp")).toString(),
            needsNarrativeRegen = false
        )

        coEvery { embeddingProvider.isReady() } returns false
        coEvery { notificationRepo.getUnembedded(any(), any()) } returns emptyList()
        coEvery { notificationRepo.getUnprocessedMatters(any(), any()) } returns listOf(newNotif)
        coEvery { topicRepo.getActiveTopicsInWindow(any(), any()) } returns listOf(existingTopic)
        coEvery { notificationRepo.getByIds(listOf("n1")) } returns listOf(existingNotif)
        coEvery { topicRepo.getById("t1") } returns existingTopic
        coEvery { notificationRepo.getRecentNotificationsSnapshot() } returns emptyList()

        engine.generateTopics()

        coVerify { topicRepo.updateTopicMembers(eq("t1"), any(), any(), any()) }
        coVerify { topicRepo.markDirty("t1") }
    }

    @Test
    fun `creates new topic when score below threshold`() = runTest {
        val existingVec = makeVec(1f, 0f, 0f)
        val newVec = makeVec(0f, 1f, 0f) // orthogonal — score ~0

        val existingNotif = notification(id = "n1", embedding = VectorMath.toBytes(existingVec))
        val newNotif = notification(id = "n2", title = "Amazon", content = "Your order shipped",
            appName = "Gmail", packageName = "com.google.android.gm",
            embedding = VectorMath.toBytes(newVec))

        val existingTopic = TopicEntity(
            id = "t1",
            headline = "Mom: Are you coming?",
            summary = "test",
            category = ClassificationResult.MATTERS,
            notificationIds = JSONArray(listOf("n1")).toString(),
            sourceApps = JSONArray(listOf("WhatsApp")).toString()
        )

        coEvery { embeddingProvider.isReady() } returns false
        coEvery { notificationRepo.getUnembedded(any(), any()) } returns emptyList()
        coEvery { notificationRepo.getUnprocessedMatters(any(), any()) } returns listOf(newNotif)
        coEvery { topicRepo.getActiveTopicsInWindow(any(), any()) } returns listOf(existingTopic)
        coEvery { notificationRepo.getByIds(listOf("n1")) } returns listOf(existingNotif)
        coEvery { notificationRepo.getRecentNotificationsSnapshot() } returns emptyList()

        engine.generateTopics()

        coVerify { topicRepo.saveTopic(any()) }
    }

    @Test
    fun `skips notifications with null embedding`() = runTest {
        val notif = notification(id = "n1", embedding = null)

        coEvery { embeddingProvider.isReady() } returns false
        coEvery { notificationRepo.getUnembedded(any(), any()) } returns emptyList()
        coEvery { notificationRepo.getUnprocessedMatters(any(), any()) } returns listOf(notif)
        coEvery { topicRepo.getActiveTopicsInWindow(any(), any()) } returns emptyList()
        coEvery { notificationRepo.getRecentNotificationsSnapshot() } returns emptyList()

        engine.generateTopics()

        coVerify { notificationRepo.markProcessedForTopics(listOf("n1")) }
        coVerify(exactly = 0) { topicRepo.saveTopic(any()) }
    }

    @Test
    fun `full rebuild resets flags and clears topics`() = runTest {
        coEvery { embeddingProvider.isReady() } returns false
        coEvery { notificationRepo.getUnembedded(any(), any()) } returns emptyList()
        coEvery { notificationRepo.getUnprocessedMatters(any(), any()) } returns emptyList()
        coEvery { notificationRepo.getRecentNotificationsSnapshot() } returns emptyList()

        engine.generateTopics(fullRebuild = true)

        coVerify { notificationRepo.resetAllProcessedFlags() }
        coVerify { topicRepo.clearAndSaveTopics(emptyList()) }
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew testDebugUnitTest --tests "com.focal.intelligence.TopicEngineTest"
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: add TopicEngine unit tests for embedding-based clustering"
```

---

## Task 7: On-Device Simulation + Verification

**Files to create:**
- `app/src/main/java/com/focal/debug/TestNotificationInjector.kt`

- [ ] **Step 1: Create test notification injector**

Create `app/src/main/java/com/focal/debug/TestNotificationInjector.kt`:
```kotlin
package com.focal.debug

import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.NotificationRepository
import com.focal.intelligence.ClassificationResult

object TestNotificationInjector {

    suspend fun injectCrossAppPersonTest(repo: NotificationRepository) {
        repo.saveNotification(NotificationEntity(
            id = "test-phone-mom",
            packageName = "com.android.phone",
            appName = "Phone",
            title = "Mom",
            content = "2 missed calls",
            postedAt = System.currentTimeMillis() - 60_000,
            category = ClassificationResult.MATTERS,
            classifiedBy = "test"
        ))
        repo.saveNotification(NotificationEntity(
            id = "test-wa-mom",
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            title = "Mom",
            content = "are you and Maya coming sunday?",
            postedAt = System.currentTimeMillis() - 30_000,
            category = ClassificationResult.MATTERS,
            classifiedBy = "test"
        ))
    }

    suspend fun injectFinancialCrossAppTest(repo: NotificationRepository) {
        repo.saveNotification(NotificationEntity(
            id = "test-sms-icici",
            packageName = "com.google.android.apps.messaging",
            appName = "Messages",
            title = "JM-ICICIT-S",
            content = "Rs 44,000.00 spent on ICICI Bank Card XX2002 on 19-Apr-26 at Amazon",
            postedAt = System.currentTimeMillis() - 60_000,
            category = ClassificationResult.MATTERS,
            classifiedBy = "test"
        ))
        repo.saveNotification(NotificationEntity(
            id = "test-tc-icici",
            packageName = "com.truecaller",
            appName = "Truecaller",
            title = "Rs 44,000.00 spent on ICICI Bank Card XX2002",
            content = "Transaction alert from ICICI Bank",
            postedAt = System.currentTimeMillis() - 55_000,
            category = ClassificationResult.MATTERS,
            classifiedBy = "test"
        ))
    }

    suspend fun injectUnrelatedTest(repo: NotificationRepository) {
        repo.saveNotification(NotificationEntity(
            id = "test-otp",
            packageName = "com.google.android.apps.messaging",
            appName = "Messages",
            title = "BX-HDFCBK",
            content = "Your OTP is 483921. Valid for 5 minutes.",
            postedAt = System.currentTimeMillis() - 60_000,
            category = ClassificationResult.MATTERS,
            classifiedBy = "test"
        ))
        repo.saveNotification(NotificationEntity(
            id = "test-flight",
            packageName = "com.google.android.apps.messaging",
            appName = "Messages",
            title = "IRCTC",
            content = "Your flight PNR 4943911221 confirmed. Delhi to Mumbai.",
            postedAt = System.currentTimeMillis() - 30_000,
            category = ClassificationResult.MATTERS,
            classifiedBy = "test"
        ))
    }
}
```

- [ ] **Step 2: Build and install**

```bash
./gradlew installDebug
```

- [ ] **Step 3: Push embedding model to device**

The Gecko embedding model needs to be on device:
```bash
ADB=/Users/shubhankar.bhadra/Library/Android/sdk/platform-tools/adb
$ADB -s 4c83eed5 shell mkdir -p /sdcard/Android/data/com.focal/files/models/embeddings/
$ADB push Gecko_256_f32.tflite /sdcard/Android/data/com.focal/files/models/embeddings/
$ADB push sentencepiece.model /sdcard/Android/data/com.focal/files/models/embeddings/
```

Note: If the Gecko model files are not available locally, download from the Google AI Edge RAG sample.

- [ ] **Step 4: Launch and verify embedding model initializes**

```bash
ADB=/Users/shubhankar.bhadra/Library/Android/sdk/platform-tools/adb
$ADB -s 4c83eed5 shell am force-stop com.focal
$ADB -s 4c83eed5 shell am start -n com.focal/.MainActivity
sleep 5
PID=$($ADB -s 4c83eed5 shell pidof com.focal)
$ADB -s 4c83eed5 logcat -d --pid=$PID | grep -E "(GeckoEmbedding|TopicEngine|Embedding)"
```

Expected: "Gecko embedding model initialized" or "Embedding model not found".

- [ ] **Step 5: Trigger refresh and verify cross-app clustering**

Pull-to-refresh on the Digest tab. Wait for the worker to complete. Check logs:
```bash
$ADB -s 4c83eed5 logcat -d --pid=$PID | grep -E "(TopicEngine|Assigned|Created new)" | tail -20
```

Verify:
- Notifications from the same person across apps are assigned to the same topic
- Unrelated notifications create separate topics
- Daily briefing is generated

- [ ] **Step 6: Commit and push**

```bash
git add -A
git commit -m "feat: cross-app story clustering with embedding-based nearest-member assignment"
git push
```

---

## Summary

7 tasks:
1. **VectorMath** — byte conversion + cosine similarity utilities with tests
2. **Database migration** — embedding, embeddedAt, processedForTopics columns + needsNarrativeRegen + queries
3. **EmbeddingProvider** — Gecko model integration via AI Edge RAG SDK + DayWindow helper
4. **TopicEngine rewrite** — embed → assign (nearest-member) → regenerate narratives pipeline
5. **DailyResetWorker** — 2 AM scheduled purge + day window reset
6. **Unit tests** — TopicEngine clustering logic with mocked embedder/LLM
7. **On-device simulation** — TestNotificationInjector + verification with real models
