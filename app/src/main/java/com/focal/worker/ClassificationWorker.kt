package com.focal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.focal.data.repository.NotificationRepository
import com.focal.intelligence.Classifier
import com.focal.intelligence.Summarizer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ClassificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationRepository: NotificationRepository,
    private val classifier: Classifier,
    private val summarizer: Summarizer
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("ClassificationWorker", "Starting batch classification")

        val pending = notificationRepository.getPendingForClassification()
        if (pending.isEmpty()) {
            Log.d("ClassificationWorker", "No pending notifications")
            return Result.success()
        }

        Log.d("ClassificationWorker", "Processing ${pending.size} pending notifications")

        var classified = 0
        for (notification in pending) {
            try {
                val result = classifier.classify(notification)
                notificationRepository.markClassified(
                    notification = notification,
                    category = result.category,
                    classifiedBy = result.classifiedBy
                )
                classified++
            } catch (e: Exception) {
                Log.e("ClassificationWorker", "Failed to classify ${notification.id}", e)
            }
        }

        Log.d("ClassificationWorker", "Classified $classified/${pending.size} notifications")

        val appPackages = pending.map { it.packageName }.distinct()
        for (packageName in appPackages) {
            try {
                val summary = summarizer.summarizeForApp(packageName)
                if (summary != null) {
                    notificationRepository.saveNotification(summary)
                    Log.d("ClassificationWorker", "Summarized: ${summary.appName}")
                }
            } catch (e: Exception) {
                Log.e("ClassificationWorker", "Failed to summarize $packageName", e)
            }
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "focal_classification"
    }
}
