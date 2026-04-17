package com.focal.di

import android.content.Context
import androidx.room.Room
import com.focal.data.db.FocalDatabase
import com.focal.data.db.dao.AppProfileDao
import com.focal.data.db.dao.CorrectionDao
import com.focal.data.db.dao.NotificationDao
import com.focal.data.db.dao.RuleDao
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
        ).build()
    }

    @Provides
    fun provideNotificationDao(db: FocalDatabase): NotificationDao = db.notificationDao()

    @Provides
    fun provideRuleDao(db: FocalDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideCorrectionDao(db: FocalDatabase): CorrectionDao = db.correctionDao()

    @Provides
    fun provideAppProfileDao(db: FocalDatabase): AppProfileDao = db.appProfileDao()
}
