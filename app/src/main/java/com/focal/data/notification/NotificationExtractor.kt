package com.focal.data.notification

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.focal.data.db.entity.NotificationEntity

class NotificationExtractor(private val packageManager: PackageManager) {

    fun extract(sbn: StatusBarNotification): NotificationEntity? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: Bundle()

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }

        val messagesJson = extractMessagingStyle(extras)

        return NotificationEntity(
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            content = content,
            bigText = bigText,
            conversation = conversation,
            postedAt = sbn.postTime,
            extrasJson = messagesJson
        )
    }

    private fun extractMessagingStyle(extras: Bundle): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages.isNullOrEmpty()) return null

        val sb = StringBuilder("[")
        messages.forEachIndexed { index, msg ->
            if (msg is Bundle) {
                val sender = msg.getCharSequence("sender")?.toString() ?: "Unknown"
                val text = msg.getCharSequence("text")?.toString() ?: ""
                if (index > 0) sb.append(",")
                sb.append("{\"sender\":\"$sender\",\"text\":\"$text\"}")
            }
        }
        sb.append("]")
        return if (sb.length > 2) sb.toString() else null
    }

    companion object {
        val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.providers.downloads"
        )
    }
}
