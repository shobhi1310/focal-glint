package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "widget_state")
data class WidgetStateEntity(
    @PrimaryKey @ColumnInfo(name = "widget_id") val widgetId: String,
    val headline: String = "",
    val subtitle: String? = null,
    val badge: String? = null,
    @ColumnInfo(name = "detail_json") val detailJson: String? = null,
    @ColumnInfo(name = "source_app_icons") val sourceAppIcons: String? = null,
    @ColumnInfo(name = "item_count") val itemCount: Int = 0,
    @ColumnInfo(name = "last_updated_at") val lastUpdatedAt: Long = System.currentTimeMillis()
)
