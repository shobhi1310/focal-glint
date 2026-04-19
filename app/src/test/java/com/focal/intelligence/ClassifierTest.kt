package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.google.ai.edge.litertlm.ToolSet
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `returns matters when tool classifies as matters`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generateWithTools(any(), any(), any()) } answers {
            val tools = thirdArg<List<ToolSet>>()
            tools.filterIsInstance<ClassifyNotificationTool>().first()
                .classifyNotification("matters", "OTP from bank")
            emptyFlow()
        }
        val result = classifier.classify(notification(content = "Your OTP is 123456"))
        assertEquals(ClassificationResult.MATTERS, result.category)
        assertEquals("llm", result.classifiedBy)
    }

    @Test
    fun `returns noise when tool classifies as noise`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generateWithTools(any(), any(), any()) } answers {
            val tools = thirdArg<List<ToolSet>>()
            tools.filterIsInstance<ClassifyNotificationTool>().first()
                .classifyNotification("noise", "Promotional offer")
            emptyFlow()
        }
        val result = classifier.classify(notification(content = "50% off today!"))
        assertEquals(ClassificationResult.NOISE, result.category)
        assertEquals("llm", result.classifiedBy)
    }

    @Test
    fun `classify uses raw system prompt without thinking prefix`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        var capturedSystemInstruction: String? = null
        coEvery { inferenceProvider.generateWithTools(any(), any(), any()) } answers {
            capturedSystemInstruction = firstArg()
            val tools = thirdArg<List<ToolSet>>()
            tools.filterIsInstance<ClassifyNotificationTool>().first()
                .classifyNotification("matters", "OTP from bank")
            emptyFlow()
        }

        val result = classifier.classify(notification(content = "Your OTP is 123456"))

        assertEquals(ClassificationResult.MATTERS, result.category)
        assertEquals("llm", result.classifiedBy)
        assertEquals("OTP from bank", result.reason)
        assertNotNull(capturedSystemInstruction)
        assertFalse(capturedSystemInstruction!!.contains("<|think|>"))
    }

    @Test
    fun `returns uncategorized when tool not called`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generateWithTools(any(), any(), any()) } returns emptyFlow()
        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("llm", result.classifiedBy)
    }

    @Test
    fun `returns uncategorized when LLM throws exception`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generateWithTools(any(), any(), any()) } throws RuntimeException("OOM")
        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("pending", result.classifiedBy)
    }

    @Test
    fun `classifyBatch processes all notifications`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generateWithTools(any(), any(), any()) } answers {
            val tools = thirdArg<List<ToolSet>>()
            tools.filterIsInstance<ClassifyNotificationTool>().first()
                .classifyNotification("noise", "promo")
            emptyFlow()
        }
        val notifications = listOf(notification(), notification(), notification())
        val results = classifier.classifyBatch(notifications)
        assertEquals(3, results.size)
        results.forEach { (_, result) -> assertEquals(ClassificationResult.NOISE, result.category) }
    }
}
