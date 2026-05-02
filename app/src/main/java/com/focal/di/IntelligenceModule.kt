package com.focal.di

import android.content.Context
import com.focal.data.db.dao.ExtractedDataDao
import com.focal.data.db.dao.TransactionDao
import com.focal.data.db.dao.WidgetConfigDao
import com.focal.data.db.dao.WidgetStateDao
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.data.repository.TopicRepository
import com.focal.data.repository.TransactionRepository
import com.focal.data.repository.WidgetRepository
import com.focal.intelligence.Classifier
import com.focal.intelligence.CloudClassifier
import com.focal.intelligence.EmbeddingProvider
import com.focal.intelligence.SwitchableEmbeddingProvider
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.LiteRtLmProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.RulesEngine
import com.focal.intelligence.TopicEngine
import com.focal.intelligence.TransactionCorrelator
import com.focal.intelligence.WidgetComputeEngine
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
    fun provideCloudClassifier(modelManager: ModelManager, widgetRepository: WidgetRepository): CloudClassifier {
        return CloudClassifier(modelManager, widgetRepository)
    }

    @Provides
    @Singleton
    fun provideClassifier(
        inferenceProvider: InferenceProvider,
        widgetRepository: WidgetRepository,
        cloudClassifier: CloudClassifier,
        modelManager: ModelManager
    ): Classifier {
        return Classifier(inferenceProvider, widgetRepository, cloudClassifier, modelManager)
    }

    @Provides
    @Singleton
    fun provideSwitchableEmbeddingProvider(): SwitchableEmbeddingProvider {
        return SwitchableEmbeddingProvider()
    }

    @Provides
    @Singleton
    fun provideEmbeddingProvider(switchable: SwitchableEmbeddingProvider): EmbeddingProvider {
        return switchable
    }

    @Provides
    @Singleton
    fun provideTopicEngine(
        embeddingProvider: EmbeddingProvider,
        notificationRepository: NotificationRepository,
        topicRepository: TopicRepository
    ): TopicEngine {
        return TopicEngine(embeddingProvider, notificationRepository, topicRepository)
    }

    @Provides
    @Singleton
    fun provideWidgetRepository(
        configDao: WidgetConfigDao,
        extractedDataDao: ExtractedDataDao,
        stateDao: WidgetStateDao
    ): WidgetRepository {
        return WidgetRepository(configDao, extractedDataDao, stateDao)
    }

    @Provides
    @Singleton
    fun provideTransactionRepository(
        transactionDao: TransactionDao,
        extractedDataDao: ExtractedDataDao
    ): TransactionRepository {
        return TransactionRepository(transactionDao, extractedDataDao)
    }

    @Provides
    @Singleton
    fun provideTransactionCorrelator(
        transactionRepository: TransactionRepository,
        widgetRepository: WidgetRepository
    ): TransactionCorrelator {
        return TransactionCorrelator(transactionRepository, widgetRepository)
    }

    @Provides
    @Singleton
    fun provideWidgetComputeEngine(
        widgetRepository: WidgetRepository,
        transactionRepository: TransactionRepository
    ): WidgetComputeEngine {
        return WidgetComputeEngine(widgetRepository, transactionRepository)
    }
}
