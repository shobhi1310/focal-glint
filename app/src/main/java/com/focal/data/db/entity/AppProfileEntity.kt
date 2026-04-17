package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_profiles")
data class AppProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_name")
    val appName: String,
    @ColumnInfo(name = "app_type")
    val appType: String = "system",
    @ColumnInfo(name = "default_category")
    val defaultCategory: String? = null,
    @ColumnInfo(name = "notification_count")
    val notificationCount: Int = 0,
    @ColumnInfo(name = "noise_ratio")
    val noiseRatio: Float = 0f,
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long = System.currentTimeMillis()
)
