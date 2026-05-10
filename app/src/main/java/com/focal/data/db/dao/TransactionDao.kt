package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.focal.data.db.entity.TransactionEntity

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE matched_notification_id IS NULL")
    suspend fun getUnmatched(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY posted_at DESC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("""
        UPDATE transactions SET
            matched_notification_id = :matchedNotificationId,
            matched_app = :matchedApp,
            matched_merchant = :matchedMerchant,
            category = :category,
            matched_at = :matchedAt
        WHERE id = :id
    """)
    suspend fun updateMatch(
        id: Long,
        matchedNotificationId: String,
        matchedApp: String,
        matchedMerchant: String,
        category: String?,
        matchedAt: Long
    )

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT * FROM transactions WHERE notification_id = :notificationId LIMIT 1")
    suspend fun getByNotificationId(notificationId: String): TransactionEntity?
}
