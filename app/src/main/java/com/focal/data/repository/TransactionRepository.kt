package com.focal.data.repository

import com.focal.data.db.dao.ExtractedDataDao
import com.focal.data.db.dao.TransactionDao
import com.focal.data.db.entity.TransactionEntity

class TransactionRepository(
    private val transactionDao: TransactionDao,
    private val extractedDataDao: ExtractedDataDao
) {
    suspend fun insert(transaction: TransactionEntity): Long {
        return transactionDao.insert(transaction)
    }

    suspend fun getUnmatched(): List<TransactionEntity> {
        return transactionDao.getUnmatched()
    }

    suspend fun getAll(): List<TransactionEntity> {
        return transactionDao.getAll()
    }

    suspend fun updateMatch(
        transactionId: Long,
        matchedNotificationId: String,
        matchedApp: String,
        matchedMerchant: String,
        category: String?
    ) {
        transactionDao.updateMatch(
            id = transactionId,
            matchedNotificationId = matchedNotificationId,
            matchedApp = matchedApp,
            matchedMerchant = matchedMerchant,
            category = category,
            matchedAt = System.currentTimeMillis()
        )
    }

    suspend fun markExtractionMatched(extractionId: Long, transactionId: Long) {
        extractedDataDao.setMatchedTransaction(extractionId, transactionId)
    }

    suspend fun deleteById(id: Long) {
        transactionDao.deleteById(id)
    }

    suspend fun getByNotificationId(notificationId: String): TransactionEntity? {
        return transactionDao.getByNotificationId(notificationId)
    }
}
