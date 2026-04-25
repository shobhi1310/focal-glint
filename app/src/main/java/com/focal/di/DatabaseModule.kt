package com.focal.di

import android.content.Context
import androidx.room.Room
import com.focal.data.db.FocalDatabase
import com.focal.data.db.dao.AppProfileDao
import com.focal.data.db.dao.CorrectionDao
import com.focal.data.db.dao.ExtractedDataDao
import com.focal.data.db.dao.NotificationDao
import com.focal.data.db.dao.RuleDao
import com.focal.data.db.dao.TopicDao
import com.focal.data.db.dao.WidgetConfigDao
import com.focal.data.db.dao.WidgetStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FocalDatabase {
        return Room.databaseBuilder(
            context,
            FocalDatabase::class.java,
            "focal_database"
        )
            .addMigrations(FocalDatabase.MIGRATION_1_2, FocalDatabase.MIGRATION_2_3, FocalDatabase.MIGRATION_3_4, FocalDatabase.MIGRATION_4_5, FocalDatabase.MIGRATION_5_6, FocalDatabase.MIGRATION_6_7, FocalDatabase.MIGRATION_7_8)
            .build()
    }

    @Provides
    fun provideNotificationDao(db: FocalDatabase): NotificationDao = db.notificationDao()

    @Provides
    fun provideRuleDao(db: FocalDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideCorrectionDao(db: FocalDatabase): CorrectionDao = db.correctionDao()

    @Provides
    fun provideAppProfileDao(db: FocalDatabase): AppProfileDao = db.appProfileDao()

    @Provides
    fun provideTopicDao(db: FocalDatabase): TopicDao = db.topicDao()

    @Provides
    fun provideWidgetConfigDao(db: FocalDatabase): WidgetConfigDao = db.widgetConfigDao()

    @Provides
    fun provideExtractedDataDao(db: FocalDatabase): ExtractedDataDao = db.extractedDataDao()

    @Provides
    fun provideWidgetStateDao(db: FocalDatabase): WidgetStateDao = db.widgetStateDao()
}
