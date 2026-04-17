package com.focal.di

import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.data.db.dao.AppProfileDao
import com.focal.data.db.dao.CorrectionDao
import com.focal.data.db.dao.NotificationDao
import com.focal.data.db.dao.RuleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideNotificationRepository(
        notificationDao: NotificationDao,
        appProfileDao: AppProfileDao
    ): NotificationRepository {
        return NotificationRepository(notificationDao, appProfileDao)
    }

    @Provides
    @Singleton
    fun provideRuleRepository(
        ruleDao: RuleDao,
        correctionDao: CorrectionDao
    ): RuleRepository {
        return RuleRepository(ruleDao, correctionDao)
    }
}
