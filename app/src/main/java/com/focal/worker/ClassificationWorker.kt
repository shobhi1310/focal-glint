package com.focal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.focal.data.repository.NotificationRepository
import com.focal.intelligence.Classifier
import com.focal.intelligence.RulesEngine
import com.focal.intelligence.TopicEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ClassificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationRepository: NotificationRepository,
    private val classifier: Classifier,
    private val rulesEngine: RulesEngine,
    private val topicEngine: TopicEngine
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("ClassificationWorker", "Starting batch processing")

        // Re-apply rules to all recent notifications (catches rule updates)
        val allRecent = notificationRepository.getRecentNotificationsSnapshot()
        var reclassified = 0
        for (notification in allRecent) {
            try {
                val ruleResult = rulesEngine.classify(notification)
                if (ruleResult != null && ruleResult.category != notification.category) {
                    notificationRepository.markClassified(
                        notification = notification,
                        category = ruleResult.category,
                        classifiedBy = ruleResult.classifiedBy,
                        ruleId = ruleResult.ruleId
                    )
                    reclassified++
                }
            } catch (e: Exception) {
                Log.w("ClassificationWorker", "Rule reclassify failed for ${notification.id}", e)
            }
        }
        if (reclassified > 0) {
            Log.d("ClassificationWorker", "Reclassified $reclassified notifications with updated rules")
        }

        val pending = notificationRepository.getPendingForClassification()
        if (pending.isNotEmpty()) {
            Log.d("ClassificationWorker", "Classifying ${pending.size} pending notifications")
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
            Log.d("ClassificationWorker", "Classified $classified/${pending.size}")
        }

        try {
            topicEngine.generateTopics()
            Log.d("ClassificationWorker", "Topic generation complete")
        } catch (e: Exception) {
            Log.e("ClassificationWorker", "Topic generation failed", e)
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "focal_classification"
    }
}
