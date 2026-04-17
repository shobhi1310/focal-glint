package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.focal.data.db.entity.CorrectionEntity

@Dao
interface CorrectionDao {
    @Insert
    suspend fun insert(correction: CorrectionEntity)

    @Query("SELECT * FROM corrections ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<CorrectionEntity>

    @Query("SELECT COUNT(*) FROM corrections")
    suspend fun count(): Int
}
