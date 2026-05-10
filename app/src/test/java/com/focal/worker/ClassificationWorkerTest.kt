package com.focal.worker

import android.content.Context
import androidx.work.WorkerParameters
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TransactionRepository
import com.focal.data.repository.WidgetRepository
import com.focal.intelligence.Classifier
import com.focal.intelligence.EngineWarmupCoordinator
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.RulesEngine
import com.focal.intelligence.TopicEngine
import com.focal.intelligence.TopicNarrativeProcessor
import com.focal.intelligence.TransactionCorrelator
import com.focal.intelligence.WidgetComputeEngine
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class InferenceWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var classifier: Classifier
    private lateinit var rulesEngine: RulesEngine
    private lateinit var topicEngine: TopicEngine
    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var modelManager: ModelManager
    private lateinit var engineWarmupCoordinator: EngineWarmupCoordinator
    private lateinit var widgetRepository: WidgetRepository
    private lateinit var widgetComputeEngine: WidgetComputeEngine
    private lateinit var topicNarrativeProcessor: TopicNarrativeProcessor
    private lateinit var transactionCorrelator: TransactionCorrelator
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var workQueue: InferenceWorkQueue

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        notificationRepository = mockk(relaxed = true)
        classifier = mockk(relaxed = true)
        rulesEngine = mockk(relaxed = true)
        topicEngine = mockk(relaxed = true)
        inferenceProvider = mockk(relaxed = true)
        modelManager = mockk(relaxed = true)
        engineWarmupCoordinator = mockk(relaxed = true)
        widgetRepository = mockk(relaxed = true)
        widgetComputeEngine = mockk(relaxed = true)
        topicNarrativeProcessor = mockk(relaxed = true)
        transactionCorrelator = mockk(relaxed = true)
        transactionRepository = mockk(relaxed = true)
        workQueue = InferenceWorkQueue()

        every { modelManager.isEngineEnabled() } returns false
        every { modelManager.isCloudEnabled() } returns false
        every { inferenceProvider.isReady() } returns false
        coEvery { notificationRepository.getRecentNotificationsSnapshot() } returns emptyList()
        coEvery { notificationRepository.getPendingForClassification() } returns emptyList()
        coEvery { notificationRepository.getBankTransactionsWithoutExtraction() } returns emptyList()
        coEvery { widgetRepository.getActiveCategories() } returns emptyList()
        coEvery { topicEngine.generateTopics() } returns Unit
        coEvery { engineWarmupCoordinator.warmEmbeddings() } returns Unit
        coEvery { topicNarrativeProcessor.processDirtyTopics() } returns false
        coEvery { transactionCorrelator.correlate() } returns Unit
        coEvery { widgetComputeEngine.computeAll() } returns Unit
    }

    private fun createWorker() = InferenceWorker(
        appContext = context,
        workerParams = workerParams,
        notificationRepository = notificationRepository,
        classifier = classifier,
        rulesEngine = rulesEngine,
        topicEngine = topicEngine,
        inferenceProvider = inferenceProvider,
        modelManager = modelManager,
        engineWarmupCoordinator = engineWarmupCoordinator,
        widgetRepository = widgetRepository,
        widgetComputeEngine = widgetComputeEngine,
        topicNarrativeProcessor = topicNarrativeProcessor,
        transactionCorrelator = transactionCorrelator,
        transactionRepository = transactionRepository,
        workQueue = workQueue
    )

    @Test
    fun `processes narrative generation after topic assignment when llm is ready`() = runTest {
        every { inferenceProvider.isReady() } returns true

        createWorker().doWork()

        coVerify { topicEngine.generateTopics() }
        coVerify { topicNarrativeProcessor.processDirtyTopics() }
    }

    @Test
    fun `engine disabled skips llm classification but still warms embeddings and generates topics`() = runTest {
        coEvery { notificationRepository.getPendingForClassification() } returns listOf(mockk(relaxed = true))

        createWorker().doWork()

        coVerify(exactly = 0) { classifier.classifyBatch(any()) }
        coVerify { engineWarmupCoordinator.warmEmbeddings() }
        coVerify { topicEngine.generateTopics() }
    }
}
