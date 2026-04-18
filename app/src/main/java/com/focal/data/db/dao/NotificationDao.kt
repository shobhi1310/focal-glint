package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.focal.data.db.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity)

    @Update
    suspend fun update(notification: NotificationEntity)

    @Query("SELECT * FROM notifications WHERE posted_at > :since ORDER BY posted_at DESC")
    fun getNotificationsSince(since: Long): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE category = :category AND posted_at > :since ORDER BY posted_at DESC")
    fun getByCategory(category: String, since: Long): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE processed_at IS NULL ORDER BY posted_at ASC")
    suspend fun getPending(): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE package_name = :packageName AND posted_at > :since ORDER BY posted_at DESC")
    suspend fun getByPackage(packageName: String, since: Long): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE category = :category AND posted_at > :since")
    fun countByCategory(category: String, since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM notifications WHERE posted_at > :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT * FROM notifications WHERE posted_at > :since ORDER BY posted_at DESC")
    suspend fun getRecentSnapshot(since: Long): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE id IN (:ids) ORDER BY posted_at DESC")
    suspend fun getByIds(ids: List<String>): List<NotificationEntity>

    @Query("DELETE FROM notifications WHERE posted_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM notifications WHERE notification_key = :key LIMIT 1")
    suspend fun getByNotificationKey(key: String): NotificationEntity?
}
