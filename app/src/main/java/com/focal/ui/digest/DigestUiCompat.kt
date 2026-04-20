package com.focal.ui.digest

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

internal val FocalAccent: Color
    get() = Color(0xFFC8956C)

@Composable
internal fun AppIcon(
    packageName: String,
    appName: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val icon: Drawable? = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    if (icon != null) {
        val bitmap = remember(icon) { icon.toBitmap().asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = appName,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = appName.take(1).uppercase(),
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
internal fun SectionHeader(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = if (count != null) "$title · $count" else title,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

internal fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffMs = nowMs - timestampMs
    val diffMinutes = diffMs / 60_000
    val diffHours = diffMs / 3_600_000

    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "${diffMinutes} min ago"
        diffHours < 24 -> "${diffHours}h ago"
        else -> "yesterday"
    }
}

internal fun getAppCategory(packageName: String): String {
    return when (packageName) {
        "com.whatsapp",
        "org.telegram.messenger",
        "com.discord",
        "com.google.android.apps.messaging",
        "com.android.phone" -> "PERSONAL"

        "com.Slack",
        "com.github.android",
        "com.microsoft.teams",
        "com.microsoft.office.outlook",
        "com.linkedin.android" -> "WORK"

        "com.cred.android",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa",
        "in.org.npci.upiapp" -> "FINANCE"

        "in.swiggy.android",
        "com.application.zomato",
        "com.ubercab",
        "com.rapido.passenger" -> "LOGISTICS"

        else -> "GENERAL"
    }
}
