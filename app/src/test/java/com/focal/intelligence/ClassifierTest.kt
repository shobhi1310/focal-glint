package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.WidgetRepository
import com.google.ai.edge.litertlm.ToolSet
import io.mockk.coEvery
import io.mockk.every
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
        packageName: String = "com.test",
        isBankTransaction: Boolean = false
    ) = NotificationEntity(
        packageName = packageName,
        appName = "Test App",
        title = title,
        content = content,
        postedAt = System.currentTimeMillis(),
        isBankTransaction = isBankTransaction
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
        assertTrue(prompt.contains("MUST call classifyNotification exactly once for EVERY [index]"))
        assertTrue(prompt.contains("no index may be skipped"))
        assertTrue(prompt.contains("Mark it 'matters'"))
        assertTrue(prompt.contains("Mark it 'noise'"))
        assertTrue(prompt.contains("Output tool calls only"))
    }

    @Test
    fun `classifyAndExtractBatch system prompt deduplicates extraction by real-world event`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        var capturedSystemInstruction: String? = null
        val session = mockk<ConversationSession>()
        coEvery { inferenceProvider.startConversation(any(), any(), any()) } answers {
            capturedSystemInstruction = firstArg()
            session
        }
        every { session.send(any()) } returns emptyFlow()
        every { session.close() } returns Unit

        classifier.classifyAndExtractBatch(
            notifications = listOf(notification(), notification()),
            extractionTools = ExtractionToolFactory.createTools(listOf("finance", "work", "personal", "logistics"))
        )

        val prompt = capturedSystemInstruction.orEmpty()
        assertTrue(prompt.contains("MUST call classifyNotification exactly once for EVERY [index]"))
        assertTrue(prompt.contains("For every notification you marked 'matters'"))
        assertTrue(prompt.contains("at least one of"))
        assertTrue(prompt.contains("one or more extraction tools if the notification clearly matches"))
        assertTrue(prompt.contains("OR noExtraction if none of the extraction tools apply"))
        assertTrue(prompt.contains("When the same real-world event appears across several notifications"))
        assertTrue(prompt.contains("call the extraction tool once for the most informative source only"))
        assertTrue(prompt.contains("Available extraction categories: finance, work, personal, logistics"))
        assertFalse(prompt.contains("Available extraction categories: finance, work, personal, logistics, none"))
    }

    @Test
    fun `classifyAndExtractBatch system prompt lists bank transaction when bank tool is injected`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        var capturedSystemInstruction: String? = null
        val session = mockk<ConversationSession>()
        coEvery { inferenceProvider.startConversation(any(), any(), any()) } answers {
            capturedSystemInstruction = firstArg()
            session
        }
        every { session.send(any()) } returns emptyFlow()
        every { session.close() } returns Unit

        classifier.classifyAndExtractBatch(
            notifications = listOf(notification(isBankTransaction = true)),
            extractionTools = ExtractionToolFactory.createTools(listOf("finance"))
        )

        val prompt = capturedSystemInstruction.orEmpty()
        assertTrue(prompt.contains("Available extraction categories: finance, bank_transaction"))
        assertFalse(prompt.contains("Available extraction categories: finance, none"))
    }

    @Test
    fun `classifyAndExtractBatch first turn is classification only`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        val session = mockk<ConversationSession>()
        val sentPrompts = mutableListOf<String>()
        coEvery { inferenceProvider.startConversation(any(), any(), any()) } returns session
        every { session.send(any()) } answers {
            sentPrompts.add(firstArg())
            emptyFlow()
        }
        every { session.close() } returns Unit

        classifier.classifyAndExtractBatch(
            notifications = listOf(notification(), notification()),
            extractionTools = ExtractionToolFactory.createTools(listOf("personal"))
        )

        assertTrue(sentPrompts.first().contains("Classify only"))
        assertTrue(sentPrompts.first().contains("Do not call extraction tools"))
    }

    @Test
    fun `classifyAndExtractBatch retries missing pass1 indices in same conversation`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        val session = mockk<ConversationSession>()
        var classifyTool: BatchClassifyNotificationTool? = null
        val sentPrompts = mutableListOf<String>()

        coEvery { inferenceProvider.startConversation(any(), any(), any()) } answers {
            val tools = secondArg<List<ToolSet>>()
            classifyTool = tools.filterIsInstance<BatchClassifyNotificationTool>().first()
            session
        }
        every { session.send(any()) } answers {
            val prompt = firstArg<String>()
            sentPrompts.add(prompt)
            when {
                sentPrompts.size == 1 -> classifyTool!!.classifyNotification(1, "matters", "personal_message")
                "Classify" in prompt && "[2]" in prompt ->
                    classifyTool!!.classifyNotification(2, "noise", "commercial_app_marketing")
            }
            emptyFlow()
        }
        every { session.close() } returns Unit

        val notifications = listOf(
            notification(title = "Alice", content = "Can you call me?"),
            notification(title = "Shop", content = "Sale starts now")
        )

        val results = classifier.classifyAndExtractBatch(
            notifications = notifications,
            extractionTools = ExtractionToolFactory.createTools(listOf("personal"))
        )

        assertEquals(ClassificationResult.MATTERS, results[0].second.category)
        assertEquals(ClassificationResult.NOISE, results[1].second.category)
        assertEquals("llm", results[1].second.classifiedBy)
        assertTrue(sentPrompts.any { "Classify" in it && "[2]" in it })
    }

    @Test
    fun `classifyAndExtractBatch skips extraction when pass1 remains incomplete`() = runTest {
        coEvery { inferenceProvider.isReady() } returns true
        val session = mockk<ConversationSession>()
        var classifyTool: BatchClassifyNotificationTool? = null
        val sentPrompts = mutableListOf<String>()

        coEvery { inferenceProvider.startConversation(any(), any(), any()) } answers {
            val tools = secondArg<List<ToolSet>>()
            classifyTool = tools.filterIsInstance<BatchClassifyNotificationTool>().first()
            session
        }
        every { session.send(any()) } answers {
            sentPrompts.add(firstArg())
            if (sentPrompts.size == 1) {
                classifyTool!!.classifyNotification(1, "matters", "personal_message")
            }
            emptyFlow()
        }
        every { session.close() } returns Unit

        val results = classifier.classifyAndExtractBatch(
            notifications = listOf(notification(), notification()),
            extractionTools = ExtractionToolFactory.createTools(listOf("personal"))
        )

        assertEquals(ClassificationResult.MATTERS, results[0].second.category)
        assertEquals("pending", results[1].second.classifiedBy)
        assertFalse(sentPrompts.any { "were marked 'matters'" in it })
    }

    @Test
    fun `classifyAndExtractBatch keeps classification results when extraction save fails`() = runTest {
        val widgetRepository = mockk<WidgetRepository>()
        classifier = Classifier(inferenceProvider, widgetRepository = widgetRepository)
        coEvery { inferenceProvider.isReady() } returns true
        coEvery { widgetRepository.saveExtractedData(any()) } throws RuntimeException("db write failed")

        val session = mockk<ConversationSession>()
        var classifyTool: BatchClassifyNotificationTool? = null
        var personalTool: ExtractPersonalTool? = null
        var sendCount = 0
        coEvery { inferenceProvider.startConversation(any(), any(), any()) } answers {
            val tools = secondArg<List<ToolSet>>()
            classifyTool = tools.filterIsInstance<BatchClassifyNotificationTool>().first()
            personalTool = tools.filterIsInstance<ExtractPersonalTool>().first()
            session
        }
        every { session.send(any()) } answers {
            sendCount++
            if (sendCount == 1) {
                classifyTool!!.classifyNotification(1, "matters", "personal_message")
            } else {
                personalTool!!.extractPersonal(1, "Alice", "message", 1, "Can you call me?")
            }
            emptyFlow()
        }
        every { session.close() } returns Unit

        val results = classifier.classifyAndExtractBatch(
            notifications = listOf(notification(title = "Alice", content = "Can you call me?")),
            extractionTools = ExtractionToolFactory.createTools(listOf("personal"))
        )

        assertEquals(ClassificationResult.MATTERS, results[0].second.category)
        assertEquals("llm", results[0].second.classifiedBy)
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
