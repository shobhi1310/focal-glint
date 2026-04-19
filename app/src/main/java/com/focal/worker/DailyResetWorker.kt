package com.focal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TopicRepository
import com.focal.intelligence.DayWindow
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DailyResetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationRepository: NotificationRepository,
    private val topicRepository: TopicRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Daily reset starting")
        val (dayStart, _) = DayWindow.getWindow()

        notificationRepository.purgeOlderThan(dayStart)
        topicRepository.clearAndSaveTopics(emptyList())
        notificationRepository.resetAllProcessedFlags()

        Log.d(TAG, "Daily reset complete — new window starts at $dayStart")
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "focal_daily_reset"
        private const val TAG = "DailyResetWorker"
    }
}
