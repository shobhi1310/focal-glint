package com.focal.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.focal.data.db.dao.AppProfileDao
import com.focal.data.db.dao.CorrectionDao
import com.focal.data.db.dao.NotificationDao
import com.focal.data.db.dao.RuleDao
import com.focal.data.db.dao.TopicDao
import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.CorrectionEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.RuleEntity
import com.focal.data.db.entity.TopicEntity

@Database(
    entities = [
        NotificationEntity::class,
        RuleEntity::class,
        CorrectionEntity::class,
        AppProfileEntity::class,
        TopicEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class FocalDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun ruleDao(): RuleDao
    abstract fun correctionDao(): CorrectionDao
    abstract fun appProfileDao(): AppProfileDao
    abstract fun topicDao(): TopicDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS topics (
                        id TEXT PRIMARY KEY NOT NULL,
                        headline TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        category TEXT NOT NULL,
                        notification_ids TEXT NOT NULL,
                        source_apps TEXT NOT NULL,
                        channel_count INTEGER NOT NULL DEFAULT 1,
                        detail_json TEXT,
                        detail_summary TEXT,
                        action_label TEXT,
                        action_package TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        is_read INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_topics_category_updated_at ON topics(category, updated_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_topics_updated_at ON topics(updated_at)")
                db.execSQL("ALTER TABLE notifications ADD COLUMN topic_id TEXT")
                db.execSQL("UPDATE notifications SET category = 'digest' WHERE category = 'informational'")
                db.execSQL("UPDATE rules SET category = 'digest' WHERE category = 'informational'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN notification_key TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE notifications SET category = 'matters' WHERE category IN ('urgent', 'actionable', 'digest')")
                db.execSQL("ALTER TABLE topics ADD COLUMN briefing_contribution TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN embedding BLOB")
                db.execSQL("ALTER TABLE notifications ADD COLUMN embedded_at INTEGER")
                db.execSQL("ALTER TABLE notifications ADD COLUMN processed_for_topics INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE topics ADD COLUMN needs_narrative_regen INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
