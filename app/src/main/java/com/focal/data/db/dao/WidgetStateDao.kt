package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.focal.data.db.entity.WidgetStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetStateDao {
    @Query("SELECT * FROM widget_state")
    fun observeAll(): Flow<List<WidgetStateEntity>>

    @Query("SELECT * FROM widget_state WHERE widget_id = :widgetId")
    fun observeById(widgetId: String): Flow<WidgetStateEntity?>

    @Query("SELECT * FROM widget_state WHERE widget_id = :widgetId")
    suspend fun getById(widgetId: String): WidgetStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: WidgetStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<WidgetStateEntity>)

    @Query("DELETE FROM widget_state WHERE widget_id = :widgetId")
    suspend fun deleteById(widgetId: String)

    @Query("DELETE FROM widget_state")
    suspend fun deleteAll()
}
