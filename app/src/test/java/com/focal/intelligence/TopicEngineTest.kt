package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
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
        val newVec = makeVec(0.95f, 0.05f, 0f)

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
        val newVec = makeVec(0f, 1f, 0f)

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
