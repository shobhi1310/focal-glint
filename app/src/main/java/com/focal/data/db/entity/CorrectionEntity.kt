package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "corrections")
data class CorrectionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "notification_id")
    val notificationId: String,
    @ColumnInfo(name = "old_category")
    val oldCategory: String,
    @ColumnInfo(name = "new_category")
    val newCategory: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
