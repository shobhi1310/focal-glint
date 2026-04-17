package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.focal.data.db.entity.AppProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppProfileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(profile: AppProfileEntity)

    @Update
    suspend fun update(profile: AppProfileEntity)

    @Query("SELECT * FROM app_profiles WHERE package_name = :packageName")
    suspend fun getByPackage(packageName: String): AppProfileEntity?

    @Query("SELECT * FROM app_profiles ORDER BY notification_count DESC")
    fun getAllProfiles(): Flow<List<AppProfileEntity>>

    @Query("UPDATE app_profiles SET notification_count = notification_count + 1, last_seen = :timestamp WHERE package_name = :packageName")
    suspend fun incrementCount(packageName: String, timestamp: Long)
}
