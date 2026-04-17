package com.focal.data.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.room.Room
import com.focal.data.db.FocalDatabase
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.RulesEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FocalNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var extractor: NotificationExtractor
    private lateinit var repository: NotificationRepository
    private lateinit var rulesEngine: RulesEngine

    override fun onCreate() {
        super.onCreate()
        extractor = NotificationExtractor(packageManager)

        val db = Room.databaseBuilder(
            applicationContext,
            FocalDatabase::class.java,
            "focal_database"
        ).build()

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

        serviceScope.launch {
            val entity = extractor.extract(sbn)
            if (entity == null) {
                Log.w("FocalListener", "Failed to extract notification from ${sbn.packageName}")
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

            repository.saveNotification(classified)
            Log.d("FocalListener", "Saved: ${classified.title} -> ${classified.category} (${classified.classifiedBy})")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed for v1
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
