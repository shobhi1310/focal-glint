package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ClassifierTest {

    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var classifier: Classifier

    @Before
    fun setup() {
        inferenceProvider = mockk(relaxed = true)
        classifier = Classifier(inferenceProvider)
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
    fun `returns matters when LLM says matters is true`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } returns
            """{"matters": true, "reason": "OTP from bank"}"""

        val result = classifier.classify(notification(content = "Your OTP is 123456"))
        assertEquals(ClassificationResult.MATTERS, result.category)
        assertEquals("llm", result.classifiedBy)
    }

    @Test
    fun `returns noise when LLM says matters is false`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } returns
            """{"matters": false, "reason": "Promotional"}"""

        val result = classifier.classify(notification(content = "50% off today!"))
        assertEquals(ClassificationResult.NOISE, result.category)
        assertEquals("llm", result.classifiedBy)
    }

    @Test
    fun `returns uncategorized when LLM response unparseable`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } returns "I don't know"

        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("llm", result.classifiedBy)
    }

    @Test
    fun `returns uncategorized when LLM throws exception`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } throws RuntimeException("OOM")

        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("pending", result.classifiedBy)
    }

    @Test
    fun `classifyBatch processes all notifications`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generate(any(), any()) } returns
            """{"matters": false, "reason": "promo"}"""

        val notifications = listOf(notification(), notification(), notification())
        val results = classifier.classifyBatch(notifications)
        assertEquals(3, results.size)
        results.forEach { (_, result) -> assertEquals(ClassificationResult.NOISE, result.category) }
    }
}
