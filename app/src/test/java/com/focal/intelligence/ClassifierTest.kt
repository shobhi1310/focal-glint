package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.google.ai.edge.litertlm.ToolSet
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } answers {
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
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } answers {
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
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } answers {
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
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } returns emptyFlow()
        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("llm", result.classifiedBy)
    }

    @Test
    fun `returns uncategorized when LLM throws exception`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } throws RuntimeException("OOM")
        val result = classifier.classify(notification())
        assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
        assertEquals("pending", result.classifiedBy)
    }

    @Test
    fun `classifyBatch returns pending when LLM not ready`() = runTest {
        coEvery { inferenceProvider.isReady() } returns false
        val notifications = listOf(notification(), notification())
        val results = classifier.classifyBatch(notifications)
        assertEquals(2, results.size)
        results.forEach { (_, result) ->
            assertEquals(ClassificationResult.UNCATEGORIZED, result.category)
            assertEquals("pending", result.classifiedBy)
        }
    }

    @Test
    fun `classifyBatch processes all notifications using BatchClassifyNotificationTool`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } answers {
            val tools = thirdArg<List<ToolSet>>()
            val batchTool = tools.filterIsInstance<BatchClassifyNotificationTool>().first()
            batchTool.classifyNotification(1, "noise", "promotional")
            batchTool.classifyNotification(2, "matters", "personal message")
            batchTool.classifyNotification(3, "noise", "spam")
            emptyFlow()
        }
        val notifications = listOf(notification(), notification(), notification())
        val results = classifier.classifyBatch(notifications)
        assertEquals(3, results.size)
        assertEquals(ClassificationResult.NOISE, results[0].second.category)
        assertEquals(ClassificationResult.MATTERS, results[1].second.category)
        assertEquals(ClassificationResult.NOISE, results[2].second.category)
        results.forEach { (_, result) -> assertEquals("llm", result.classifiedBy) }
    }

    @Test
    fun `classifyBatch system prompt requires one classify tool call per index`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        var capturedSystemInstruction: String? = null
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } answers {
            capturedSystemInstruction = firstArg()
            emptyFlow()
        }

        classifier.classifyBatch(listOf(notification(), notification()))

        val prompt = capturedSystemInstruction.orEmpty()
        assertTrue(prompt.contains("tool calls only"))
        assertTrue(prompt.contains("For every [index], call classifyNotification exactly once"))
        assertTrue(prompt.contains("Use category exactly 'matters'"))
        assertTrue(prompt.contains("Use category exactly 'noise'"))
        assertTrue(prompt.contains("No prose"))
    }

    @Test
    fun `classifyAndExtractBatch system prompt deduplicates extraction by real-world event`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        var capturedSystemInstruction: String? = null
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } answers {
            capturedSystemInstruction = firstArg()
            emptyFlow()
        }

        classifier.classifyAndExtractBatch(
            notifications = listOf(notification(), notification()),
            extractionTools = ExtractionToolFactory.createTools(listOf("finance", "work", "personal", "logistics"))
        )

        val prompt = capturedSystemInstruction.orEmpty()
        assertTrue(prompt.contains("internally compare notifications across the whole batch"))
        assertTrue(prompt.contains("Do not output reasoning"))
        assertTrue(prompt.contains("distinct real-world events, not notification count"))
        assertTrue(prompt.contains("call the extraction tool only once"))
        assertTrue(prompt.contains("same rounded amount plus same direction"))
        assertTrue(prompt.contains("prefer bank SMS"))
        assertTrue(prompt.contains("collapse same sender plus same channel"))
        assertTrue(prompt.contains("collapse same merchant/order flow"))
        assertTrue(prompt.contains("collapse same sender plus same work entity/thread"))
        assertTrue(prompt.contains("only when required fields are explicit"))
        assertTrue(prompt.contains("Available extraction categories: finance, work, personal, logistics"))
    }

    @Test
    fun `classifyBatch returns pending for unclassified index`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { inferenceProvider.generateWithTools(any(), any(), any(), any()) } answers {
            val tools = thirdArg<List<ToolSet>>()
            val batchTool = tools.filterIsInstance<BatchClassifyNotificationTool>().first()
            batchTool.classifyNotification(1, "noise", "promo")
            // index 2 intentionally not called — model skipped it
            emptyFlow()
        }
        val notifications = listOf(notification(), notification())
        val results = classifier.classifyBatch(notifications)
        assertEquals(ClassificationResult.NOISE, results[0].second.category)
        assertEquals("llm", results[0].second.classifiedBy)
        assertEquals(ClassificationResult.UNCATEGORIZED, results[1].second.category)
        assertEquals("pending", results[1].second.classifiedBy)
    }

    @Test
    fun `synthetic tool echo with parentheses is ignored`() {
        assertTrue(
            isSyntheticToolEcho(
                """classifyNotification(category="noise", reason="promo")"""
            )
        )
    }

    @Test
    fun `synthetic tool echo with braces is ignored`() {
        assertTrue(
            isSyntheticToolEcho(
                """classifyNotification{category:<|"|>matters<|"|>,reason:<|"|>bank OTP<|"|>}"""
            )
        )
    }

    @Test
    fun `tool execution is detected from captured category`() {
        val tool = ClassifyNotificationTool()
        tool.classifyNotification("matters", "OTP from bank")

        assertTrue(wasToolExecuted(tool))
    }

    @Test
    fun `unexpected prose warning is suppressed when tool already executed`() {
        assertFalse(
            shouldWarnAboutUnexpectedProse(
                """classifyNotification(category="noise", reason="promo")""",
                toolExecuted = true
            )
        )
    }

    @Test
    fun `unexpected prose warning still fires when tool did not execute`() {
        assertTrue(
            shouldWarnAboutUnexpectedProse(
                "some random prose from model",
                toolExecuted = false
            )
        )
    }
}
