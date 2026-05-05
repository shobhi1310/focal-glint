package com.focal.ui.digest

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import com.focal.intelligence.TopicEngine
import com.focal.worker.InferenceWorker
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
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        topicRepository = mockk(relaxed = true)
        notificationRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        every { topicRepository.getStoryTopics() } returns flowOf(emptyList())
        every { notificationRepository.countByCategory(any()) } returns flowOf(0)
        every { notificationRepository.totalCount() } returns flowOf(0)

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
        context = context
    )

    @Test
    fun `refresh enqueues inference work without warming engine on ui refresh`() = runTest {
        val vm = createViewModel()
        TopicEngine.pendingFullRebuild.set(false)

        vm.onRefresh()
        advanceUntilIdle()

        assert(!TopicEngine.pendingFullRebuild.get())
        verify {
            workManager.enqueueUniqueWork(
                eq(InferenceWorker.WORK_NAME),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `refresh enqueues inference work even when engine state is unknown`() = runTest {
        val vm = createViewModel()

        vm.onRefresh()
        advanceUntilIdle()

        verify {
            workManager.enqueueUniqueWork(
                eq(InferenceWorker.WORK_NAME),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>()
            )
        }
    }
}
