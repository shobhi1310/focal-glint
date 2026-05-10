package com.focal.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.focal.intelligence.BankSmsDetector
import com.focal.intelligence.RulesEngine
import com.focal.worker.InferenceWorker
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

    // Notification keys of redacted SMS that need re-extraction on unlock
    private val redactedSmsKeys = mutableSetOf<String>()

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT && redactedSmsKeys.isNotEmpty()) {
                Log.d("FocalListener", "Phone unlocked — re-extracting ${redactedSmsKeys.size} redacted SMS")
                reExtractRedactedNotifications()
            }
        }
    }

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
            .addMigrations(FocalDatabase.MIGRATION_1_2, FocalDatabase.MIGRATION_2_3, FocalDatabase.MIGRATION_3_4, FocalDatabase.MIGRATION_4_5, FocalDatabase.MIGRATION_5_6, FocalDatabase.MIGRATION_6_7, FocalDatabase.MIGRATION_7_8, FocalDatabase.MIGRATION_8_9)
            .build()

        repository = NotificationRepository(db.notificationDao(), db.appProfileDao())
        val ruleRepository = RuleRepository(db.ruleDao(), db.correctionDao())
        rulesEngine = RulesEngine(ruleRepository)

        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
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

            if (entity.packageName == "com.google.android.apps.messaging"
                && entity.content.contains("Sensitive notification content hidden")) {
                redactedSmsKeys.add(sbn.key)
                Log.d("FocalListener", "Redacted SMS detected, queued for re-extraction: ${sbn.key}")
            }

            val isBankTxn = BankSmsDetector.isBankTransaction(
                entity.packageName, entity.title, entity.content
            )

            val ruleResult = rulesEngine.classify(entity)
            val classified = if (ruleResult != null) {
                entity.copy(
                    category = ruleResult.category,
                    classifiedBy = ruleResult.classifiedBy,
                    ruleId = ruleResult.ruleId,
                    processedAt = System.currentTimeMillis(),
                    isBankTransaction = isBankTxn
                )
            } else {
                entity.copy(isBankTransaction = isBankTxn)
            }

            repository.upsertNotification(classified)
            Log.d("FocalListener", "Saved: ${classified.title} -> ${classified.category} (${classified.classifiedBy}) bankTxn=$isBankTxn")

            // Enqueue background classification/topic worker with 30s delay to batch rapid notifications
            val workRequest = OneTimeWorkRequestBuilder<InferenceWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                InferenceWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        redactedSmsKeys.remove(sbn.key)
    }

    private fun reExtractRedactedNotifications() {
        val keysToProcess = redactedSmsKeys.toSet()
        redactedSmsKeys.clear()

        val activeNotifications = try {
            getActiveNotifications() ?: emptyArray()
        } catch (e: Exception) {
            Log.w("FocalListener", "Failed to get active notifications", e)
            return
        }

        var reExtracted = 0
        for (sbn in activeNotifications) {
            if (sbn.key !in keysToProcess) continue

            serviceScope.launch {
                val entity = extractor.extract(sbn) ?: return@launch
                if (entity.content.contains("Sensitive notification content hidden")) {
                    Log.d("FocalListener", "Still redacted after unlock: ${sbn.key}")
                    return@launch
                }

                Log.d("FocalListener", "Re-extracted: ${entity.appName} - ${entity.title}: ${entity.content}")

                val isBankTxn = BankSmsDetector.isBankTransaction(
                    entity.packageName, entity.title, entity.content
                )

                val ruleResult = rulesEngine.classify(entity)
                val classified = if (ruleResult != null) {
                    entity.copy(
                        category = ruleResult.category,
                        classifiedBy = ruleResult.classifiedBy,
                        ruleId = ruleResult.ruleId,
                        processedAt = System.currentTimeMillis(),
                        isBankTransaction = isBankTxn
                    )
                } else {
                    entity.copy(isBankTransaction = isBankTxn)
                }

                repository.upsertNotification(classified)
                Log.d("FocalListener", "Re-saved: ${classified.title} -> ${classified.category} bankTxn=$isBankTxn")
            }
            reExtracted++
        }

        if (reExtracted > 0) {
            val workRequest = OneTimeWorkRequestBuilder<InferenceWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                InferenceWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) { }
        if (wakeLock.isHeld) wakeLock.release()
        serviceScope.cancel()
    }
}
