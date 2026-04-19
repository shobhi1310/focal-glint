package com.focal.intelligence

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.focal.data.db.entity.NotificationEntity

object ActionIntentResolver {

    fun resolve(context: Context, action: SuggestedAction, notifications: List<NotificationEntity> = emptyList()): Intent? {
        return when (action.type) {
            "call" -> {
                val number = extractPhoneNumber(notifications)
                if (number != null) Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                else launchApp(context, action.packageName)
            }
            "view" -> {
                val url = extractUrl(notifications)
                if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url))
                else launchApp(context, action.packageName)
            }
            else -> launchApp(context, action.packageName)
        }
    }

    private fun launchApp(context: Context, packageName: String): Intent? {
        if (packageName.isBlank()) return null
        return context.packageManager.getLaunchIntentForPackage(packageName)
    }

    private fun extractPhoneNumber(notifications: List<NotificationEntity>): String? {
        val allText = notifications.joinToString(" ") { "${it.title} ${it.content} ${it.bigText ?: ""}" }
        val phoneRegex = Regex("(?:\\+91|0)?[6-9]\\d{9}")
        return phoneRegex.find(allText)?.value?.let {
            if (it.startsWith("+")) it else "+91$it"
        }
    }

    private fun extractUrl(notifications: List<NotificationEntity>): String? {
        val allText = notifications.joinToString(" ") { "${it.title} ${it.content} ${it.bigText ?: ""}" }
        val urlRegex = Regex("https?://\\S+")
        return urlRegex.find(allText)?.value
    }
}
