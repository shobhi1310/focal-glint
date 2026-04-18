package com.focal.ui.digest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.TopicEntity
import com.focal.intelligence.ClassificationResult
import com.focal.ui.theme.ActionableAmber
import com.focal.ui.theme.DigestBlue
import com.focal.ui.theme.UrgentRed
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TopicDetailScreen(
    onBack: () -> Unit,
    viewModel: TopicDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "\u2190 Back to Digest",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 32.dp)
            )
        } else if (state.topic == null) {
            Text(
                text = "Topic not found",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 32.dp)
            )
        } else {
            val topic = state.topic!!

            CategoryBadge(category = topic.category)

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = topic.headline,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Detail card or summary
            if (topic.detailJson != null) {
                DetailCard(detailJson = topic.detailJson)
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (topic.detailSummary != null) {
                Text(
                    text = topic.detailSummary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (topic.detailJson == null && topic.detailSummary == null) {
                Text(
                    text = topic.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Source notifications
            if (state.notifications.isNotEmpty()) {
                Text(
                    text = "RECEIVED VIA ${state.notifications.size} CHANNELS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                state.notifications.forEachIndexed { index, notification ->
                    SourceNotificationItem(notification = notification)
                    if (index < state.notifications.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            // Action button
            if (topic.actionLabel != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { /* Intent to open app would go here */ },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = topic.actionLabel)
                }
            }
        }
    }
}

@Composable
private fun CategoryBadge(category: String) {
    val color = when (category) {
        ClassificationResult.URGENT -> UrgentRed
        ClassificationResult.ACTIONABLE -> ActionableAmber
        ClassificationResult.DIGEST -> DigestBlue
        else -> Color.Gray
    }

    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = category.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DetailCard(detailJson: String) {
    val parsed = parseDetailJson(detailJson) ?: return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            parsed.forEach { (label, value) ->
                DetailRow(label, value)
            }
        }
    }
}

private fun parseDetailJson(detailJson: String): List<Pair<String, String>>? {
    return try {
        val json = JSONObject(detailJson)
        val type = json.optString("type", "")
        val rows = mutableListOf<Pair<String, String>>()

        when (type) {
            "billing" -> {
                rows.add("Amount" to json.optString("amount", "-"))
                json.optString("due_date", "").takeIf { it.isNotBlank() }?.let {
                    rows.add("Due date" to it)
                }
                json.optString("card_last4", "").takeIf { it.isNotBlank() }?.let {
                    rows.add("Card" to "****$it")
                }
            }
            "transactional" -> {
                rows.add("Status" to json.optString("status", "-"))
                json.optString("latest_update", "").takeIf { it.isNotBlank() }?.let {
                    rows.add("Update" to it)
                }
            }
            "calendar" -> {
                rows.add("Event" to json.optString("event_name", "-"))
                json.optString("details", "").takeIf { it.isNotBlank() }?.let {
                    rows.add("Details" to it)
                }
            }
            else -> {
                rows.add("Detail" to detailJson)
            }
        }

        rows.ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SourceNotificationItem(notification: NotificationEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = notification.appName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatDetailTime(notification.postedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = notification.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = notification.bigText ?: notification.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 3
        )
    }
}

private fun formatDetailTime(timestamp: Long): String {
    val format = SimpleDateFormat("h:mm a", Locale.getDefault())
    return format.format(Date(timestamp))
}
