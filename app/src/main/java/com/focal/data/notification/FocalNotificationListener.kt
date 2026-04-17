package com.focal.data.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.room.Room
import com.focal.data.db.FocalDatabase
import com.focal.data.repository.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FocalNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var extractor: NotificationExtractor
    private lateinit var repository: NotificationRepository

    override fun onCreate() {
        super.onCreate()
        extractor = NotificationExtractor(packageManager)

        val db = Room.databaseBuilder(
            applicationContext,
            FocalDatabase::class.java,
            "focal_database"
        ).build()
        repository = NotificationRepository(db.notificationDao(), db.appProfileDao())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        if (NotificationExtractor.IGNORED_PACKAGES.contains(sbn.packageName)) return
        if (sbn.isOngoing) return

        serviceScope.launch {
            val entity = extractor.extract(sbn) ?: return@launch
            repository.saveNotification(entity)
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
