package com.focal.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.focal.data.db.dao.AppProfileDao
import com.focal.data.db.dao.CorrectionDao
import com.focal.data.db.dao.ExtractedDataDao
import com.focal.data.db.dao.NotificationDao
import com.focal.data.db.dao.RuleDao
import com.focal.data.db.dao.TopicDao
import com.focal.data.db.dao.WidgetConfigDao
import com.focal.data.db.dao.WidgetStateDao
import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.CorrectionEntity
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.RuleEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity

@Database(
    entities = [
        NotificationEntity::class,
        RuleEntity::class,
        CorrectionEntity::class,
        AppProfileEntity::class,
        TopicEntity::class,
        WidgetConfigEntity::class,
        ExtractedDataEntity::class,
        WidgetStateEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class FocalDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun ruleDao(): RuleDao
    abstract fun correctionDao(): CorrectionDao
    abstract fun appProfileDao(): AppProfileDao
    abstract fun topicDao(): TopicDao
    abstract fun widgetConfigDao(): WidgetConfigDao
    abstract fun extractedDataDao(): ExtractedDataDao
    abstract fun widgetStateDao(): WidgetStateDao

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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE topics ADD COLUMN suggested_actions TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN content_hash TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notification_key ON notifications(notification_key)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN extracted_categories TEXT")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS widget_configs (
                        id TEXT PRIMARY KEY NOT NULL,
                        category TEXT NOT NULL,
                        title TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        extraction_tool TEXT NOT NULL,
                        field TEXT,
                        group_by TEXT,
                        filter_apps TEXT,
                        headline_template TEXT NOT NULL,
                        source TEXT NOT NULL DEFAULT 'TEMPLATE',
                        position INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )"""
                )

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS extracted_data (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        notification_id TEXT NOT NULL,
                        category TEXT NOT NULL,
                        data TEXT NOT NULL,
                        app_package TEXT NOT NULL,
                        extracted_at INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extracted_data_notification_id ON extracted_data(notification_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extracted_data_category ON extracted_data(category)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS widget_state (
                        widget_id TEXT PRIMARY KEY NOT NULL,
                        headline TEXT NOT NULL DEFAULT '',
                        subtitle TEXT,
                        badge TEXT,
                        detail_json TEXT,
                        source_app_icons TEXT,
                        item_count INTEGER NOT NULL DEFAULT 0,
                        last_updated_at INTEGER NOT NULL
                    )"""
                )
            }
        }
    }
}
