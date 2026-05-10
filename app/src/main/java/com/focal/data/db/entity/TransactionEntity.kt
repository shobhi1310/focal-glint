package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "notification_id") val notificationId: String,
    val amount: Double,
    val direction: String,
    val account: String,
    val bank: String,
    @ColumnInfo(name = "raw_merchant") val rawMerchant: String? = null,
    @ColumnInfo(name = "matched_notification_id") val matchedNotificationId: String? = null,
    @ColumnInfo(name = "matched_app") val matchedApp: String? = null,
    @ColumnInfo(name = "matched_merchant") val matchedMerchant: String? = null,
    val category: String? = null,
    @ColumnInfo(name = "posted_at") val postedAt: Long,
    @ColumnInfo(name = "matched_at") val matchedAt: Long? = null
)
