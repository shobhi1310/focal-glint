package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val app: String? = null,
    val pattern: String? = null,
    val category: String,
    val confidence: Float = 1.0f,
    @ColumnInfo(name = "hit_count")
    val hitCount: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    val source: String
)
