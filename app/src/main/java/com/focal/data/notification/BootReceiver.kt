package com.focal.data.notification

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import com.focal.intelligence.ModelManager
import com.focal.service.LlmForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            NotificationListenerService.requestRebind(
                ComponentName(context, FocalNotificationListener::class.java)
            )
            val modelManager = ModelManager(context)
            if (modelManager.isEngineEnabled() && modelManager.activeVariant() != null) {
                context.startForegroundService(Intent(context, LlmForegroundService::class.java))
            }
        }
    }
}
