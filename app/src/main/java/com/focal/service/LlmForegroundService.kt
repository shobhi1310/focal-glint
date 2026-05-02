package com.focal.service

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
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import com.focal.intelligence.TopicEngine
import com.focal.worker.InferenceWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LlmForegroundService : Service() {

    @Inject lateinit var inferenceProvider: InferenceProvider
    @Inject lateinit var modelManager: ModelManager

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
            Log.d(TAG, "Warm engine unavailable — stopping service without cold load")
            stopSelf()
            return START_NOT_STICKY
        }
        if (inferenceProvider.isReady() && modelManager.getPendingRebuild()) {
            modelManager.setPendingRebuild(false)
            TopicEngine.pendingFullRebuild.set(true)
            WorkManager.getInstance(this).enqueueUniqueWork(
                InferenceWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<InferenceWorker>().build()
            )
            Log.d(TAG, "Triggered full topic rebuild with warm engine")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
