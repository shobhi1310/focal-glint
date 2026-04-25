package com.focal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.TopicNarrativeProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TopicNarrativeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelManager: ModelManager,
    private val inferenceProvider: InferenceProvider,
    private val topicNarrativeProcessor: TopicNarrativeProcessor
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!modelManager.isEngineEnabled()) {
            Log.d(TAG, "Narrative worker skipped: engine disabled")
            return Result.success()
        }
        if (!inferenceProvider.isReady()) {
            Log.d(TAG, "Narrative worker skipped: engineEnabled=${modelManager.isEngineEnabled()} llmReady=${inferenceProvider.isReady()}")
            return Result.retry()
        }

        val hasMore = topicNarrativeProcessor.processDirtyTopics()
        if (hasMore) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TopicNarrativeWorker>().build()
            )
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "focal_topic_narratives"
        private const val TAG = "TopicNarrativeWorker"
    }
}
