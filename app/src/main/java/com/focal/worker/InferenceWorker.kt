package com.focal.worker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TransactionEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.TransactionRepository
import com.focal.data.repository.WidgetRepository
import com.focal.intelligence.Classifier
import com.focal.intelligence.EngineWarmupCoordinator
import com.focal.intelligence.ExtractionToolFactory
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.RulesEngine
import com.focal.intelligence.TopicEngine
import com.focal.intelligence.TopicNarrativeProcessor
import com.focal.intelligence.TransactionCorrelator
import com.focal.intelligence.WidgetComputeEngine
import com.focal.service.LlmForegroundService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull

@HiltWorker
class InferenceWorker @AssistedInject constructor(
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
    private val widgetComputeEngine: WidgetComputeEngine,
    private val topicNarrativeProcessor: TopicNarrativeProcessor,
    private val transactionCorrelator: TransactionCorrelator,
    private val transactionRepository: TransactionRepository,
    private val workQueue: InferenceWorkQueue
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "InferenceWorker starting, queueSize=${workQueue.size()}")

        if (modelManager.isEngineEnabled()) {
            applicationContext.startForegroundService(
                Intent(applicationContext, LlmForegroundService::class.java)
            )
        }

        // Always enqueue classification — it handles the "no pending" case internally
        workQueue.enqueue(WorkType.CLASSIFY_PENDING, WorkPriority.HIGH)

        // Process queue in priority order
        var processedAny = false
        while (!workQueue.isEmpty()) {
            val item = workQueue.poll() ?: break
            Log.d(TAG, "Processing: ${item.type} priority=${item.priority}")

            val success = when (item.type) {
                WorkType.CLASSIFY_PENDING -> doClassification()
                WorkType.GENERATE_NARRATIVES -> doNarrativeGeneration()
            }

            if (success) processedAny = true

            if (item.priority == WorkPriority.LOW && workQueue.hasHighPriority()) {
                Log.d(TAG, "High-priority work arrived, preempting low-priority queue")
            }
        }

        Log.d(TAG, "InferenceWorker complete, processedAny=$processedAny")
        return Result.success()
    }

    private suspend fun doClassification(): Boolean {
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
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Rule reclassify failed for ${notification.id}", e) }
        }
        if (reclassified > 0) Log.d(TAG, "Reclassified $reclassified notifications with updated rules")

        val pendingClassification = notificationRepository.getPendingForClassification()
        val bankNeedingExtraction = notificationRepository.getBankTransactionsWithoutExtraction()
        val pending = (pendingClassification + bankNeedingExtraction).distinctBy { it.id }

        if (pending.isEmpty()) {
            Log.d(TAG, "No pending notifications to classify")
            runTopicGeneration()
            return true
        }

        Log.d(TAG, "Pending: ${pendingClassification.size} classification + ${bankNeedingExtraction.size} bank extraction")

        val cloudEnabled = modelManager.isCloudEnabled()
        if (!cloudEnabled && !inferenceProvider.isReady()) {
            if (!modelManager.isEngineEnabled()) {
                Log.d(TAG, "Engine disabled and cloud off — skipping classification")
            } else {
                val deadline = System.currentTimeMillis() + 45_000
                while (!inferenceProvider.isReady() && System.currentTimeMillis() < deadline) {
                    delay(500)
                }
                if (!inferenceProvider.isReady()) {
                    Log.w(TAG, "LLM not ready after 45s wait")
                    // Don't return retry — just skip classification, proceed to topics
                    runTopicGeneration()
                    return false
                }
            }
        }

        if (cloudEnabled || inferenceProvider.isReady()) {
            val activeCategories = widgetRepository.getActiveCategories()
            val extractionTools = if (activeCategories.isNotEmpty()) {
                ExtractionToolFactory.createTools(activeCategories)
            } else emptyMap()

            Log.d(TAG, "Classifying ${pending.size} notifications (extraction: $activeCategories)")
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
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Batch classification failed", e) }
            }
            Log.d(TAG, "Classified $classified/${pending.size}")
        }

        saveBankTransactions(pending)
        runTopicGeneration()
        return true
    }

    private suspend fun saveBankTransactions(notifications: List<NotificationEntity>) {
        val bankNotifs = notifications.filter { it.isBankTransaction }
        if (bankNotifs.isEmpty()) return

        for (notif in bankNotifs) {
            val existing = transactionRepository.getByNotificationId(notif.id)
            if (existing != null) continue

            val extractions = widgetRepository.getExtractedDataForNotification(notif.id, "bank_transaction")
            for (extraction in extractions) {
                try {
                    val data = Json.parseToJsonElement(extraction.data).jsonObject
                    val txn = TransactionEntity(
                        notificationId = notif.id,
                        amount = data["amount"]?.jsonPrimitive?.doubleOrNull ?: continue,
                        direction = data["direction"]?.jsonPrimitive?.content ?: continue,
                        account = data["account"]?.jsonPrimitive?.content ?: "",
                        bank = data["bank"]?.jsonPrimitive?.content ?: "",
                        rawMerchant = data["merchant"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                        postedAt = notif.postedAt
                    )
                    transactionRepository.insert(txn)
                    Log.d(TAG, "Created transaction: ₹${txn.amount} ${txn.direction} via ${txn.bank} ${txn.account}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create transaction from ${notif.id}", e)
                }
            }
        }
    }

    private suspend fun runTopicGeneration() {
        try {
            engineWarmupCoordinator.warmEmbeddings()
            topicEngine.generateTopics()
            // After topics are generated, enqueue narrative generation as low priority
            workQueue.enqueue(WorkType.GENERATE_NARRATIVES, WorkPriority.LOW)
            Log.d(TAG, "Topic generation complete, narratives enqueued")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Topic generation failed", e) }

        try {
            transactionCorrelator.correlate()
            Log.d(TAG, "Transaction correlation complete")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Transaction correlation failed", e) }

        try {
            widgetComputeEngine.computeAll()
            Log.d(TAG, "Widget compute complete")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Widget compute failed", e) }
    }

    private suspend fun doNarrativeGeneration(): Boolean {
        if (!inferenceProvider.isReady()) {
            Log.d(TAG, "Narrative skipped: LLM not ready (will be picked up next cycle)")
            return false
        }

        Log.d(TAG, "Starting narrative generation")
        var generated = 0
        // Process narratives one at a time, checking for high-priority preemption between each
        while (true) {
            // Check if high-priority work arrived
            if (workQueue.hasHighPriority()) {
                Log.d(TAG, "Narrative paused: high-priority work arrived after $generated narratives")
                // Re-enqueue ourselves so we resume after high-pri work
                workQueue.enqueue(WorkType.GENERATE_NARRATIVES, WorkPriority.LOW)
                return true
            }

            val hasMore = topicNarrativeProcessor.processDirtyTopics()
            generated++
            if (!hasMore) {
                Log.d(TAG, "Narrative generation complete: $generated topics processed")
                return true
            }
        }
    }

    companion object {
        const val WORK_NAME = "focal_inference"
        private const val TAG = "InferenceWorker"

        suspend fun cancelAndWait(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME)
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .first { infos -> infos.isEmpty() || infos.all { it.state.isFinished } }
        }
    }
}
