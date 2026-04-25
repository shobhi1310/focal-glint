package com.focal.ui.digest

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import com.focal.intelligence.EngineWarmupCoordinator
import com.focal.intelligence.ModelManager
import com.focal.worker.ClassificationWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DigestViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var topicRepository: TopicRepository
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var context: Context
    private lateinit var modelManager: ModelManager
    private lateinit var engineWarmupCoordinator: EngineWarmupCoordinator
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        topicRepository = mockk(relaxed = true)
        notificationRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        modelManager = mockk(relaxed = true)
        engineWarmupCoordinator = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        every { topicRepository.getStoryTopics() } returns flowOf(emptyList())
        every { notificationRepository.countByCategory(any()) } returns flowOf(0)
        every { notificationRepository.totalCount() } returns flowOf(0)
        every { modelManager.isEngineEnabled() } returns true
        coEvery { engineWarmupCoordinator.warmUp() } returns true

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList<WorkInfo>())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(WorkManager::class)
    }

    private fun createViewModel() = DigestViewModel(
        topicRepository = topicRepository,
        notificationRepository = notificationRepository,
        context = context,
        modelManager = modelManager,
        engineWarmupCoordinator = engineWarmupCoordinator
    )

    @Test
    fun `refresh warms engine before enqueueing rebuild when engine is enabled`() = runTest {
        val vm = createViewModel()

        vm.onRefresh()
        advanceUntilIdle()

        coVerify { engineWarmupCoordinator.warmUp() }
        verify {
            workManager.enqueueUniqueWork(
                eq(ClassificationWorker.WORK_NAME),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `refresh does not warm engine when engine is disabled`() = runTest {
        every { modelManager.isEngineEnabled() } returns false
        val vm = createViewModel()

        vm.onRefresh()
        advanceUntilIdle()

        coVerify(exactly = 0) { engineWarmupCoordinator.warmUp() }
        verify {
            workManager.enqueueUniqueWork(
                eq(ClassificationWorker.WORK_NAME),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>()
            )
        }
    }
}
