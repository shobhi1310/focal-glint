package com.focal

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Configuration
import androidx.work.WorkManager
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.DefaultRules
import com.focal.intelligence.TopicClusteringPolicy
import com.focal.intelligence.TopicEngine
import com.focal.ui.theme.ThemePreference
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.focal.worker.ClassificationWorker
import com.focal.worker.DailyResetWorker
import javax.inject.Inject

@HiltAndroidApp
class FocalApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var ruleRepository: RuleRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ThemePreference.initialize(this)
        DebugLogger.init(this)
        seedDefaultRules()
        migrateTopicClusteringIfNeeded()
        scheduleDailyReset()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun seedDefaultRules() {
        val prefs = getSharedPreferences("focal_prefs", MODE_PRIVATE)
        val currentVersion = 5
        if (prefs.getInt("rules_seed_version", 0) < currentVersion) {
            applicationScope.launch {
                ruleRepository.replaceSystemDefaults(DefaultRules.get())
                prefs.edit().putInt("rules_seed_version", currentVersion).apply()
            }
        }
    }

    private fun scheduleDailyReset() {
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelay = target.timeInMillis - now.timeInMillis

        val request = androidx.work.PeriodicWorkRequestBuilder<DailyResetWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyResetWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun migrateTopicClusteringIfNeeded() {
        val prefs = getSharedPreferences("focal_prefs", MODE_PRIVATE)
        val storedVersion = prefs.getInt("topic_clustering_config_version", 0)
        if (!TopicClusteringPolicy.needsFullRebuild(storedVersion)) return

        TopicEngine.pendingFullRebuild.set(true)
        val request = OneTimeWorkRequestBuilder<ClassificationWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            ClassificationWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        prefs.edit()
            .putInt("topic_clustering_config_version", TopicClusteringPolicy.CONFIG_VERSION)
            .apply()
    }
}
