package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["package_name", "posted_at"]),
        Index(value = ["category", "posted_at"]),
        Index(value = ["processed_at"])
    ]
)
data class NotificationEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "app_name")
    val appName: String,

    val title: String,

    val content: String,

    @ColumnInfo(name = "big_text")
    val bigText: String? = null,

    val category: String = "uncategorized",

    @ColumnInfo(name = "classified_by")
    val classifiedBy: String = "pending",

    @ColumnInfo(name = "rule_id")
    val ruleId: String? = null,

    val conversation: String? = null,

    @ColumnInfo(name = "posted_at")
    val postedAt: Long,

    @ColumnInfo(name = "captured_at")
    val capturedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "processed_at")
    val processedAt: Long? = null,

    @ColumnInfo(name = "is_summary")
    val isSummary: Boolean = false,

    @ColumnInfo(name = "summary_text")
    val summaryText: String? = null,

    @ColumnInfo(name = "extras_json")
    val extrasJson: String? = null,

    @ColumnInfo(name = "topic_id")
    val topicId: String? = null,

    @ColumnInfo(name = "notification_key")
    val notificationKey: String? = null
)
