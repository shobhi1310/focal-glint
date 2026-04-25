package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "extracted_data",
    indices = [Index(value = ["notification_id"]), Index(value = ["category"])]
)
data class ExtractedDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "notification_id") val notificationId: String,
    val category: String,
    val data: String,
    @ColumnInfo(name = "app_package") val appPackage: String,
    @ColumnInfo(name = "extracted_at") val extractedAt: Long = System.currentTimeMillis()
)
