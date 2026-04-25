package com.focal.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.WidgetRepository
import com.focal.intelligence.Classifier
import com.focal.intelligence.EngineWarmupCoordinator
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.RulesEngine
import com.focal.intelligence.TopicEngine
import com.focal.intelligence.TopicNarrativeProcessor
import com.focal.intelligence.WidgetComputeEngine
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class ClassificationWorkerTest {

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
    private lateinit var workManager: WorkManager

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
        workManager = mockk(relaxed = true)

        every { modelManager.isEngineEnabled() } returns false
        every { inferenceProvider.isReady() } returns false
        coEvery { notificationRepository.getRecentNotificationsSnapshot() } returns emptyList()
        coEvery { notificationRepository.getPendingForClassification() } returns emptyList()
        coEvery { topicEngine.generateTopics() } returns Unit
        coEvery { engineWarmupCoordinator.warmEmbeddings() } returns Unit

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    private fun createWorker() = ClassificationWorker(
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
        widgetComputeEngine = widgetComputeEngine
    )

    @Test
    fun `enqueues topic narrative worker after topic assignment`() = runTest {
        createWorker().doWork()

        verify {
            workManager.enqueueUniqueWork(
                eq(TopicNarrativeWorker.WORK_NAME),
                eq(ExistingWorkPolicy.KEEP),
                any<OneTimeWorkRequest>()
            )
        }
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

class TopicNarrativeWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var modelManager: ModelManager
    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var topicNarrativeProcessor: TopicNarrativeProcessor
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
        modelManager = mockk(relaxed = true)
        inferenceProvider = mockk(relaxed = true)
        topicNarrativeProcessor = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        every { modelManager.isEngineEnabled() } returns true
        every { inferenceProvider.isReady() } returns true
        coEvery { topicNarrativeProcessor.processDirtyTopics() } returns true

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    private fun createWorker() = TopicNarrativeWorker(
        appContext = context,
        workerParams = workerParams,
        modelManager = modelManager,
        inferenceProvider = inferenceProvider,
        topicNarrativeProcessor = topicNarrativeProcessor
    )

    @Test
    fun `enqueues continuation work and succeeds when more dirty topics remain`() = runTest {
        val result = createWorker().doWork()

        org.junit.Assert.assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        verify {
            workManager.enqueueUniqueWork(
                eq(TopicNarrativeWorker.WORK_NAME),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                any<OneTimeWorkRequest>()
            )
        }
    }
}
