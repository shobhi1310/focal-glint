package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.focal.data.db.entity.RuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<RuleEntity>)

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("SELECT * FROM rules WHERE source = 'user_explicit' ORDER BY created_at DESC")
    suspend fun getUserRules(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE source = 'llm_extracted' ORDER BY created_at DESC")
    suspend fun getLlmRules(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE source = 'system_default' ORDER BY created_at DESC")
    suspend fun getSystemRules(): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY source, created_at DESC")
    fun getAllRules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE app = :packageName ORDER BY source")
    suspend fun getRulesForApp(packageName: String): List<RuleEntity>

    @Query("DELETE FROM rules WHERE id = :ruleId")
    suspend fun delete(ruleId: String)
}
