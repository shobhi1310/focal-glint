package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "topics",
    indices = [
        Index(value = ["category", "updated_at"]),
        Index(value = ["updated_at"])
    ]
)
data class TopicEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val headline: String,
    val summary: String,
    val category: String,
    @ColumnInfo(name = "notification_ids")
    val notificationIds: String,
    @ColumnInfo(name = "source_apps")
    val sourceApps: String,
    @ColumnInfo(name = "channel_count")
    val channelCount: Int = 1,
    @ColumnInfo(name = "detail_json")
    val detailJson: String? = null,
    @ColumnInfo(name = "detail_summary")
    val detailSummary: String? = null,
    @ColumnInfo(name = "action_label")
    val actionLabel: String? = null,
    @ColumnInfo(name = "action_package")
    val actionPackage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false
)
