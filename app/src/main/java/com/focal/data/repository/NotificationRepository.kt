package com.focal.data.repository

import com.focal.data.db.dao.AppProfileDao
import com.focal.data.db.dao.NotificationDao
import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

class NotificationRepository(
    private val notificationDao: NotificationDao,
    private val appProfileDao: AppProfileDao
) {
    private val twentyFourHoursMs = 24 * 60 * 60 * 1000L

    fun getRecentNotifications(): Flow<List<NotificationEntity>> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return notificationDao.getNotificationsSince(since)
    }

    fun getByCategory(category: String): Flow<List<NotificationEntity>> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return notificationDao.getByCategory(category, since)
    }

    fun countByCategory(category: String): Flow<Int> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return notificationDao.countByCategory(category, since)
    }

    fun totalCount(): Flow<Int> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return notificationDao.countSince(since)
    }

    suspend fun getPendingForClassification(): List<NotificationEntity> {
        return notificationDao.getPending()
    }

    suspend fun saveNotification(notification: NotificationEntity) {
        notificationDao.insert(notification)
        appProfileDao.insertIfNew(
            AppProfileEntity(
                packageName = notification.packageName,
                appName = notification.appName
            )
        )
        appProfileDao.incrementCount(notification.packageName, notification.capturedAt)
    }

    suspend fun markClassified(notification: NotificationEntity, category: String, classifiedBy: String, ruleId: String? = null) {
        notificationDao.update(
            notification.copy(
                category = category,
                classifiedBy = classifiedBy,
                ruleId = ruleId,
                processedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getRecentNotificationsSnapshot(): List<NotificationEntity> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return notificationDao.getRecentSnapshot(since)
    }

    suspend fun purgeOld() {
        val cutoff = System.currentTimeMillis() - twentyFourHoursMs
        notificationDao.deleteOlderThan(cutoff)
    }

    fun getAppProfiles(): Flow<List<AppProfileEntity>> {
        return appProfileDao.getAllProfiles()
    }

    suspend fun getAppProfile(packageName: String): AppProfileEntity? {
        return appProfileDao.getByPackage(packageName)
    }

    suspend fun getNotificationsByPackage(packageName: String): List<NotificationEntity> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return notificationDao.getByPackage(packageName, since)
    }

    suspend fun getByIds(ids: List<String>): List<NotificationEntity> {
        return notificationDao.getByIds(ids)
    }
}
