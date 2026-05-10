package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.focal.data.db.entity.WidgetConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetConfigDao {
    @Query("SELECT * FROM widget_configs ORDER BY position ASC")
    fun observeAll(): Flow<List<WidgetConfigEntity>>

    @Query("SELECT * FROM widget_configs ORDER BY position ASC")
    suspend fun getAll(): List<WidgetConfigEntity>

    @Query("SELECT DISTINCT category FROM widget_configs")
    suspend fun getActiveCategories(): List<String>

    @Query("SELECT * FROM widget_configs WHERE id = :id")
    suspend fun getById(id: String): WidgetConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: WidgetConfigEntity)

    @Update
    suspend fun update(config: WidgetConfigEntity)

    @Query("DELETE FROM widget_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM widget_configs")
    suspend fun count(): Int
}
