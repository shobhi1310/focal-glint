package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test

class TopicNarrativeProcessorTest {

    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var notificationRepo: NotificationRepository
    private lateinit var topicRepo: TopicRepository
    private lateinit var processor: TopicNarrativeProcessor

    @Before
    fun setup() {
        inferenceProvider = mockk(relaxed = true)
        notificationRepo = mockk(relaxed = true)
        topicRepo = mockk(relaxed = true)
        processor = TopicNarrativeProcessor(inferenceProvider, notificationRepo, topicRepo)
    }

    @After
    fun tearDown() {
        try {
            unmockkStatic(Log::class)
        } catch (_: Exception) {
        }
    }

    private fun notification(
        id: String,
        title: String,
        content: String,
        appName: String = "Teams"
    ) = NotificationEntity(
        id = id,
        packageName = "com.teams",
        appName = appName,
        title = title,
        content = content,
        postedAt = System.currentTimeMillis(),
        category = ClassificationResult.MATTERS
    )

    @Test
    fun `logs narrative fallback reason when topic summary parse fails`() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        val topic = TopicEntity(
            id = "t1",
            headline = "Team thread",
            summary = "old",
            category = ClassificationResult.MATTERS,
            notificationIds = JSONArray(listOf("n1", "n2")).toString(),
            sourceApps = JSONArray(listOf("Teams")).toString(),
            needsNarrativeRegen = true
        )
        val first = notification(id = "n1", title = "Alice", content = "Can you review this?")
        val second = notification(id = "n2", title = "Bob", content = "I will check it.")

        coEvery { topicRepo.getActiveTopicsInWindow(any(), any()) } returnsMany listOf(listOf(topic), emptyList())
        coEvery { notificationRepo.getByIds(listOf("n1", "n2")) } returns listOf(first, second)
        every { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any(), any()) } returns ""

        processor.processDirtyTopics()

        verify {
            Log.d("TopicEngine", match { it.contains("Narrative fallback") && it.contains("reason=parse_failed") })
        }
        coVerify { topicRepo.updateTopicHeadline("t1", "Teams · 2 messages", any(), null) }
        coVerify { topicRepo.markClean("t1") }
    }

    @Test
    fun `processDirtyTopics handles one llm topic per run and reports more work`() = runTest {
        val firstTopic = TopicEntity(
            id = "t1",
            headline = "First",
            summary = "old",
            category = ClassificationResult.MATTERS,
            notificationIds = JSONArray(listOf("n1", "n2")).toString(),
            sourceApps = JSONArray(listOf("Teams")).toString(),
            needsNarrativeRegen = true
        )
        val secondTopic = firstTopic.copy(id = "t2")
        val first = notification(id = "n1", title = "Alice", content = "Can you review this?")
        val second = notification(id = "n2", title = "Bob", content = "I will check it.")

        coEvery { topicRepo.getActiveTopicsInWindow(any(), any()) } returnsMany listOf(
            listOf(firstTopic, secondTopic),
            listOf(secondTopic)
        )
        coEvery { notificationRepo.getByIds(listOf("n1", "n2")) } returns listOf(first, second)
        every { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any(), any()) } returns "TITLE: Team Review\nSUMMARY: Alice asked Bob to review the work."

        val hasMore = processor.processDirtyTopics()

        org.junit.Assert.assertTrue(hasMore)
        coVerify(exactly = 1) { inferenceProvider.generate(any(), any(), any()) }
        coVerify { topicRepo.markClean("t1") }
        coVerify(exactly = 0) { topicRepo.markClean("t2") }
    }

    @Test
    fun `processDirtyTopics prioritizes multi-member dirty topics before single-member cleanup`() = runTest {
        val singleTopic = TopicEntity(
            id = "single",
            headline = "Single",
            summary = "old",
            category = ClassificationResult.MATTERS,
            notificationIds = JSONArray(listOf("s1")).toString(),
            sourceApps = JSONArray(listOf("Teams")).toString(),
            needsNarrativeRegen = true,
            updatedAt = 200L
        )
        val multiTopic = TopicEntity(
            id = "multi",
            headline = "Multi",
            summary = "old",
            category = ClassificationResult.MATTERS,
            notificationIds = JSONArray(listOf("m1", "m2")).toString(),
            sourceApps = JSONArray(listOf("Teams")).toString(),
            needsNarrativeRegen = true,
            updatedAt = 100L
        )
        val first = notification(id = "m1", title = "Alice", content = "Can you review this?")
        val second = notification(id = "m2", title = "Bob", content = "I will check it.")

        coEvery { topicRepo.getActiveTopicsInWindow(any(), any()) } returnsMany listOf(
            listOf(singleTopic, multiTopic),
            emptyList()
        )
        coEvery { notificationRepo.getByIds(listOf("m1", "m2")) } returns listOf(first, second)
        every { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any(), any()) } returns "TITLE: Team Review\nSUMMARY: Alice asked Bob to review the work."

        processor.processDirtyTopics()

        coVerify { topicRepo.updateTopicHeadline("multi", "Team Review", "Alice asked Bob to review the work.", null) }
        coVerify(exactly = 0) { topicRepo.markClean("single") }
    }
}
