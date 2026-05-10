package com.focal.data.notification

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
        } catch (e: Exception) {
            // Fallback: try to get app name from notification extras
            notification.extras?.getString("android.appInfo.label")
                ?: sbn.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
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
            extrasJson = messagesJson,
            notificationKey = sbn.key
        )
    }

    private fun extractMessagingStyle(extras: Bundle): String? {
        val messageBundles = getMessageBundles(extras) ?: return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messageBundles)

        val sb = StringBuilder("[")
        messageBundles.forEachIndexed { index, parcelable ->
            val bundle = parcelable as? Bundle
            val parsedMessage = messages.getOrNull(index)
            val sender = bundle?.getCharSequence("sender")?.toString()
                ?: parsedMessage?.senderPerson?.name?.toString()
                ?: "Unknown"
            val text = bundle?.getCharSequence("text")?.toString()
                ?: parsedMessage?.text?.toString()
                ?: ""
            if (index > 0) sb.append(",")
            sb.append("{\"sender\":\"$sender\",\"text\":\"$text\"}")
        }
        sb.append("]")
        return if (sb.length > 2) sb.toString() else null
    }

    private fun getMessageBundles(extras: Bundle): Array<Parcelable>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
    }

    companion object {
        val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.providers.downloads",
            "com.android.mtp"
        )

        private val SYSTEM_NOISE_PATTERNS = listOf(
            "checking for new messages",
            "looking for new messages",
            "syncing new emails",
            "downloading messages",
            "connecting to"
        )

        fun isSystemNoise(title: String, content: String): Boolean {
            val combined = "$title $content".lowercase()
            return SYSTEM_NOISE_PATTERNS.any { combined.contains(it) }
        }
    }
}
