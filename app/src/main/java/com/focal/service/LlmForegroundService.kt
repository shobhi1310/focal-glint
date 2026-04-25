package com.focal.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.intelligence.GemmaEmbeddingProvider
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelBackendPolicy
import com.focal.intelligence.ModelManager
import com.focal.intelligence.SwitchableEmbeddingProvider
import com.focal.intelligence.TopicEngine
import com.focal.worker.ClassificationWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LlmForegroundService : Service() {

    @Inject lateinit var inferenceProvider: InferenceProvider
    @Inject lateinit var embeddingProvider: SwitchableEmbeddingProvider
    @Inject lateinit var modelManager: ModelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!modelManager.isEngineEnabled()) {
            Log.d(TAG, "Engine disabled — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-assert foreground on every start command — recovers priority if notification was dismissed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (!inferenceProvider.isReady()) {
            val isRestart = intent == null // null = OS restarted after kill
            scope.launch { initializeEngines(isRestart) }
        }
        return START_STICKY
    }

    private suspend fun waitForMemory(requiredMb: Long = 600L): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        val deadline = System.currentTimeMillis() + 120_000L
        while (System.currentTimeMillis() < deadline) {
            am.getMemoryInfo(info)
            val availMb = info.availMem / 1_000_000L
            if (!info.lowMemory && availMb >= requiredMb) return true
            Log.d(TAG, "Waiting for memory: ${availMb}MB available lowMemory=${info.lowMemory}")
            delay(5_000)
        }
        Log.w(TAG, "Memory wait timed out — proceeding anyway")
        return false
    }

    private suspend fun initializeEngines(isRestart: Boolean = false) {
        if (isRestart) {
            // Previous process was OOM-killed. GPU memory (OpenCL) is freed by the driver
            // asynchronously — availMem won't reflect it. Wait before attempting to reload.
            Log.d(TAG, "Restarting after kill — waiting 15s for GPU memory to settle")
            delay(15_000)
        }
        waitForMemory()
        val variant = modelManager.getSelectedVariant() ?: modelManager.activeVariant()
        if (variant != null) {
            val modelFile = modelManager.modelFileFor(variant)
            val useGpu = modelManager.getBackendPreference()
            try {
                inferenceProvider.initialize(modelFile.absolutePath, useGpu, variant.maxContextTokens)
                Log.d(TAG, "LLM initialized: ${variant.displayName} gpu=$useGpu")
            } catch (e: Exception) {
                Log.e(TAG, "LLM init failed (${e.javaClass.simpleName}): ${e.message}")
                if (useGpu) {
                    try {
                        inferenceProvider.initialize(modelFile.absolutePath, false, variant.maxContextTokens)
                        Log.d(TAG, "LLM initialized with CPU fallback")
                    } catch (e2: Exception) {
                        Log.e(TAG, "CPU fallback also failed: ${e2.message}")
                    }
                }
            }
        }

        if (modelManager.isGemmaEmbeddingAvailable && !embeddingProvider.isReady()) {
            embeddingProvider.inner = GemmaEmbeddingProvider(this)
            val useGpu = ModelBackendPolicy.useGpuForEmbeddings(modelManager.getBackendPreference())
            try {
                embeddingProvider.initialize(
                    modelManager.gemmaEmbeddingModelFile.absolutePath,
                    modelManager.tokenizerFile.absolutePath,
                    useGpu
                )
                Log.d(TAG, "EmbeddingGemma initialized gpu=$useGpu")
                if (modelManager.getPendingRebuild()) {
                    modelManager.setPendingRebuild(false)
                    TopicEngine.pendingFullRebuild.set(true)
                    WorkManager.getInstance(this).enqueueUniqueWork(
                        ClassificationWorker.WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<ClassificationWorker>().build()
                    )
                    Log.d(TAG, "Triggered full topic rebuild after engine init")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Embedding init failed", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Focal AI Engine", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the AI engine running in the background"
                setShowBadge(false)
            }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focal AI")
            .setContentText("Processing notifications in the background")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LlmForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "focal_llm_service"
    }
}
