package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.focal.data.db.entity.TopicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TopicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(topic: TopicEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(topics: List<TopicEntity>)

    @Update
    suspend fun update(topic: TopicEntity)

    @Query("SELECT * FROM topics WHERE updated_at > :since ORDER BY CASE category WHEN 'urgent' THEN 0 WHEN 'actionable' THEN 1 WHEN 'digest' THEN 2 WHEN 'noise' THEN 3 END, updated_at DESC")
    fun getTopicsSince(since: Long): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE category = :category AND updated_at > :since ORDER BY updated_at DESC")
    fun getByCategory(category: String, since: Long): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE id = :topicId")
    suspend fun getById(topicId: String): TopicEntity?

    @Query("SELECT COUNT(*) FROM topics WHERE updated_at > :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM topics WHERE category = :category AND updated_at > :since")
    fun countByCategory(category: String, since: Long): Flow<Int>

    @Query("DELETE FROM topics WHERE updated_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM topics WHERE headline = 'BRIEFING' AND updated_at > :since LIMIT 1")
    fun getBriefing(since: Long): Flow<TopicEntity?>

    @Query("SELECT * FROM topics WHERE headline != 'BRIEFING' AND updated_at > :since ORDER BY updated_at DESC")
    fun getStoryTopics(since: Long): Flow<List<TopicEntity>>

    @Query("DELETE FROM topics")
    suspend fun deleteAll()
}
