package com.focal.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.focal.data.db.dao.AppProfileDao
import com.focal.data.db.dao.CorrectionDao
import com.focal.data.db.dao.NotificationDao
import com.focal.data.db.dao.RuleDao
import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.CorrectionEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.RuleEntity

@Database(
    entities = [
        NotificationEntity::class,
        RuleEntity::class,
        CorrectionEntity::class,
        AppProfileEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FocalDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun ruleDao(): RuleDao
    abstract fun correctionDao(): CorrectionDao
    abstract fun appProfileDao(): AppProfileDao
}
