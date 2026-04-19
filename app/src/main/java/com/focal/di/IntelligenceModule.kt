package com.focal.di

import android.content.Context
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.data.repository.TopicRepository
import com.focal.intelligence.Classifier
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.LiteRtLmProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.RulesEngine
import com.focal.intelligence.TopicEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    @Provides
    @Singleton
    fun provideInferenceProvider(@ApplicationContext context: Context): InferenceProvider {
        return LiteRtLmProvider(context)
    }

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager {
        return ModelManager(context)
    }

    @Provides
    @Singleton
    fun provideClassifier(inferenceProvider: InferenceProvider): Classifier {
        return Classifier(inferenceProvider)
    }

    @Provides
    @Singleton
    fun provideTopicEngine(
        inferenceProvider: InferenceProvider,
        notificationRepository: NotificationRepository,
        topicRepository: TopicRepository
    ): TopicEngine {
        return TopicEngine(inferenceProvider, notificationRepository, topicRepository)
    }
}
