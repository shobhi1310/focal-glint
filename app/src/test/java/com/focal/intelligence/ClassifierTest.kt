package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ClassifierTest {

    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var ruleRepository: RuleRepository
    private lateinit var classifier: Classifier

    @Before
    fun setup() {
        inferenceProvider = mockk(relaxed = true)
        notificationRepository = mockk(relaxed = true)
        ruleRepository = mockk(relaxed = true)
        classifier = Classifier(inferenceProvider, notificationRepository, ruleRepository)
    }

    private fun notification(
        title: String = "Test",
        content: String = "test content",
        packageName: String = "com.test"
    ) = NotificationEntity(
        packageName = packageName,
        appName = "Test App",
        title = title,
        content = content,
        postedAt = System.currentTimeMillis()
    )

    @Test
    fun `returns uncategorized when LLM not ready`() = runTest {
        coEvery { inferenceProvider.isReady() } returns false
        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("pending", result.classifiedBy)
    }

    @Test
    fun `returns LLM classification when response is valid`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } returns
            """{"category": "urgent", "reason": "OTP detected", "confidence": 0.95}"""
        coEvery { notificationRepository.getAppProfile(any()) } returns null
        coEvery { ruleRepository.getRecentCorrections(any()) } returns emptyList()

        val result = classifier.classify(notification(content = "Your OTP is 123456"))
        assertEquals("urgent", result.category)
        assertEquals("llm", result.classifiedBy)
        assertEquals(0.95f, result.confidence, 0.01f)
    }

    @Test
    fun `returns uncategorized when LLM response unparseable`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } returns "I don't know what this is"
        coEvery { notificationRepository.getAppProfile(any()) } returns null
        coEvery { ruleRepository.getRecentCorrections(any()) } returns emptyList()

        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("llm", result.classifiedBy)
    }

    @Test
    fun `returns uncategorized when LLM throws exception`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } throws RuntimeException("OOM")
        coEvery { notificationRepository.getAppProfile(any()) } returns null
        coEvery { ruleRepository.getRecentCorrections(any()) } returns emptyList()

        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("pending", result.classifiedBy)
    }

    @Test
    fun `classifyBatch processes all notifications`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } returns
            """{"category": "noise", "reason": "promo", "confidence": 0.8}"""
        coEvery { notificationRepository.getAppProfile(any()) } returns null
        coEvery { ruleRepository.getRecentCorrections(any()) } returns emptyList()

        val notifications = listOf(notification(), notification(), notification())
        val results = classifier.classifyBatch(notifications)
        assertEquals(3, results.size)
        results.forEach { (_, result) -> assertEquals("noise", result.category) }
    }
}
