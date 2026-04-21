package com.focal.data.notification

import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.db.FocalDatabase
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.RulesEngine
import com.focal.worker.ClassificationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class FocalNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var extractor: NotificationExtractor
    private lateinit var repository: NotificationRepository
    private lateinit var rulesEngine: RulesEngine
    private lateinit var wakeLock: PowerManager.WakeLock

    // Keyed by notificationKey+title+content; values are timestamps. Main-thread only — no lock needed.
    private val recentlySeen = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "focal:NotificationListener").apply { acquire() }
        extractor = NotificationExtractor(packageManager)

        val db = Room.databaseBuilder(
            applicationContext,
            FocalDatabase::class.java,
            "focal_database"
        )
            .addMigrations(FocalDatabase.MIGRATION_1_2, FocalDatabase.MIGRATION_2_3, FocalDatabase.MIGRATION_3_4, FocalDatabase.MIGRATION_4_5, FocalDatabase.MIGRATION_5_6, FocalDatabase.MIGRATION_6_7)
            .build()

        repository = NotificationRepository(db.notificationDao(), db.appProfileDao())
        val ruleRepository = RuleRepository(db.ruleDao(), db.correctionDao())
        rulesEngine = RulesEngine(ruleRepository)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        Log.d("FocalListener", "onNotificationPosted: ${sbn?.packageName} - ${sbn?.notification?.extras?.getCharSequence("android.title")}")
        sbn ?: return
        if (sbn.packageName == packageName) return
        if (NotificationExtractor.IGNORED_PACKAGES.contains(sbn.packageName)) return
        if (sbn.isOngoing) return
        if (sbn.notification?.flags?.and(android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: ""
        val text  = extras?.getCharSequence("android.text")?.toString() ?: ""
        val dedupKey = "${sbn.key}|$title|$text"
        val now = System.currentTimeMillis()
        if (now - (recentlySeen[dedupKey] ?: 0L) < 10_000L) return
        recentlySeen[dedupKey] = now
        if (recentlySeen.size > 200) recentlySeen.entries.removeIf { now - it.value > 30_000L }

        serviceScope.launch {
            val entity = extractor.extract(sbn)
            if (entity == null) {
                Log.w("FocalListener", "Failed to extract notification from ${sbn.packageName}")
                return@launch
            }

            if (NotificationExtractor.isSystemNoise(entity.title, entity.content)) {
                Log.d("FocalListener", "Filtered system noise: ${entity.appName} - ${entity.title}")
                return@launch
            }

            Log.d("FocalListener", "Captured: ${entity.appName} - ${entity.title}: ${entity.content}")

            val ruleResult = rulesEngine.classify(entity)
            val classified = if (ruleResult != null) {
                entity.copy(
                    category = ruleResult.category,
                    classifiedBy = ruleResult.classifiedBy,
                    ruleId = ruleResult.ruleId,
                    processedAt = System.currentTimeMillis()
                )
            } else {
                entity
            }

            repository.upsertNotification(classified)
            Log.d("FocalListener", "Saved: ${classified.title} -> ${classified.category} (${classified.classifiedBy})")

            // Enqueue background classification/topic worker with 30s delay to batch rapid notifications
            val workRequest = OneTimeWorkRequestBuilder<ClassificationWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                ClassificationWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed for v1
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock.isHeld) wakeLock.release()
        serviceScope.cancel()
    }
}
