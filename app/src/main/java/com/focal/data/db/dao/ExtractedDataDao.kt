package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.focal.data.db.entity.ExtractedDataEntity

@Dao
interface ExtractedDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: ExtractedDataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<ExtractedDataEntity>)

    @Query("SELECT * FROM extracted_data WHERE category = :category")
    suspend fun getByCategory(category: String): List<ExtractedDataEntity>

    @Query("SELECT * FROM extracted_data WHERE category = :category AND app_package IN (:packages)")
    suspend fun getByCategoryAndApps(category: String, packages: List<String>): List<ExtractedDataEntity>

    @Query("SELECT * FROM extracted_data WHERE notification_id = :notificationId")
    suspend fun getByNotificationId(notificationId: String): List<ExtractedDataEntity>

    @Query("DELETE FROM extracted_data WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM extracted_data WHERE category = :category")
    suspend fun deleteByCategory(category: String)

    @Query("DELETE FROM extracted_data WHERE category = :category AND notification_id IN (SELECT id FROM notifications WHERE package_name IN (:packages))")
    suspend fun deleteByCategoryAndApps(category: String, packages: List<String>)

    @Query("DELETE FROM extracted_data")
    suspend fun deleteAll()

    @Query("SELECT * FROM extracted_data WHERE category = 'finance' AND matched_transaction_id IS NULL")
    suspend fun getUnmatchedFinance(): List<ExtractedDataEntity>

    @Query("UPDATE extracted_data SET matched_transaction_id = :transactionId WHERE id = :id")
    suspend fun setMatchedTransaction(id: Long, transactionId: Long)

    @Query("SELECT * FROM extracted_data WHERE notification_id = :notificationId AND category = :category")
    suspend fun getByNotificationIdAndCategory(notificationId: String, category: String): List<ExtractedDataEntity>

    @Query("DELETE FROM extracted_data WHERE notification_id = :notificationId AND category = :category")
    suspend fun deleteByNotificationIdAndCategory(notificationId: String, category: String)
}
