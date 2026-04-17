package com.focal.di

import com.focal.data.repository.RuleRepository
import com.focal.intelligence.RulesEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IntelligenceModule {

    @Provides
    @Singleton
    fun provideRulesEngine(ruleRepository: RuleRepository): RulesEngine {
        return RulesEngine(ruleRepository)
    }
}
