package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "widget_configs")
data class WidgetConfigEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val category: String,
    val title: String,
    val operation: String,
    @ColumnInfo(name = "extraction_tool") val extractionTool: String,
    val field: String? = null,
    @ColumnInfo(name = "group_by") val groupBy: String? = null,
    @ColumnInfo(name = "filter_apps") val filterApps: String? = null,
    @ColumnInfo(name = "headline_template") val headlineTemplate: String,
    val source: String = "TEMPLATE",
    val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
