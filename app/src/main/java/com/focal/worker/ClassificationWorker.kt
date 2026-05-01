package com.focal.worker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.WidgetRepository
import com.focal.intelligence.Classifier
import com.focal.intelligence.EngineWarmupCoordinator
import com.focal.intelligence.ExtractionToolFactory
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.RulesEngine
import com.focal.intelligence.TopicEngine
import com.focal.intelligence.WidgetComputeEngine
import com.focal.service.LlmForegroundService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@HiltWorker
class ClassificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notificationRepository: NotificationRepository,
    private val classifier: Classifier,
    private val rulesEngine: RulesEngine,
    private val topicEngine: TopicEngine,
    private val inferenceProvider: InferenceProvider,
    private val modelManager: ModelManager,
    private val engineWarmupCoordinator: EngineWarmupCoordinator,
    private val widgetRepository: WidgetRepository,
    private val widgetComputeEngine: WidgetComputeEngine
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("ClassificationWorker", "Starting batch processing")
        if (modelManager.isEngineEnabled()) {
            applicationContext.startForegroundService(
                Intent(applicationContext, LlmForegroundService::class.java)
            )
        }

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ClassificationWorker", "Rule reclassify failed for ${notification.id}", e)
            }
        }
        if (reclassified > 0) {
            Log.d("ClassificationWorker", "Reclassified $reclassified notifications with updated rules")
        }

        val pending = notificationRepository.getPendingForClassification()
        val cloudEnabled = modelManager.isCloudEnabled()
        if (pending.isNotEmpty()) {
            if (!cloudEnabled && !inferenceProvider.isReady()) {
                if (!modelManager.isEngineEnabled()) {
                    Log.d("ClassificationWorker", "Engine disabled and cloud off — skipping classification")
                } else {
                    val deadline = System.currentTimeMillis() + 45_000
                    while (!inferenceProvider.isReady() && System.currentTimeMillis() < deadline) {
                        delay(500)
                    }
                    if (!inferenceProvider.isReady()) {
                        Log.w("ClassificationWorker", "LLM not ready after 45s wait, will retry")
                        return Result.retry()
                    }
                }
            }
            if (cloudEnabled || inferenceProvider.isReady()) {
                val activeCategories = widgetRepository.getActiveCategories()
                val extractionTools = if (activeCategories.isNotEmpty()) {
                    ExtractionToolFactory.createTools(activeCategories)
                } else {
                    emptyMap()
                }
                Log.d("ClassificationWorker", "Classifying ${pending.size} pending notifications in batches of 10 (extraction categories: $activeCategories)")
                var classified = 0
                for (batch in pending.chunked(10)) {
                    try {
                        val results = if (extractionTools.isNotEmpty()) {
                            classifier.classifyAndExtractBatch(batch, extractionTools)
                        } else {
                            classifier.classifyBatch(batch)
                        }
                        for ((notification, result) in results) {
                            if (result.classifiedBy != "pending") {
                                notificationRepository.markClassified(
                                    notification = notification,
                                    category = result.category,
                                    classifiedBy = result.classifiedBy
                                )
                                classified++
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("ClassificationWorker", "Batch classification failed", e)
                    }
                }
                Log.d("ClassificationWorker", "Classified $classified/${pending.size}")
            }
        }

        try {
            engineWarmupCoordinator.warmEmbeddings()
            topicEngine.generateTopics()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                TopicNarrativeWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TopicNarrativeWorker>().build()
            )
            Log.d("ClassificationWorker", "Topic generation complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ClassificationWorker", "Topic generation failed", e)
        }

        try {
            widgetComputeEngine.computeAll()
            Log.d("ClassificationWorker", "Widget compute complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ClassificationWorker", "Widget compute failed", e)
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "focal_classification"

        suspend fun cancelAndWait(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME)
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .first { infos -> infos.isEmpty() || infos.all { it.state.isFinished } }
        }
    }
}
