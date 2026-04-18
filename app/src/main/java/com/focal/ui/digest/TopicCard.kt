package com.focal.ui.digest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focal.data.db.entity.TopicEntity
import com.focal.intelligence.ClassificationResult
import com.focal.ui.theme.ActionableAmber
import com.focal.ui.theme.ActionableAmberContainer
import com.focal.ui.theme.DigestBlue
import com.focal.ui.theme.DigestBlueContainer
import com.focal.ui.theme.UrgentRed
import com.focal.ui.theme.UrgentRedContainer
import org.json.JSONArray

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicCard(
    topic: TopicEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (categoryColor, categoryContainerColor) = categoryColors(topic.category)

    Surface(
        color = categoryContainerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = categoryColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = topic.category.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = categoryColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (topic.channelCount > 1) {
                    Text(
                        text = "via ${topic.channelCount} channels",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = topic.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = topic.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val sourceApps = parseSourceApps(topic.sourceApps)
            if (sourceApps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    sourceApps.forEach { app ->
                        Surface(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = app,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun categoryColors(category: String): Pair<Color, Color> {
    return when (category) {
        ClassificationResult.URGENT -> UrgentRed to UrgentRedContainer
        ClassificationResult.ACTIONABLE -> ActionableAmber to ActionableAmberContainer
        ClassificationResult.DIGEST -> DigestBlue to DigestBlueContainer
        else -> Color.Gray to Color.Gray.copy(alpha = 0.1f)
    }
}

private fun parseSourceApps(sourceAppsJson: String): List<String> {
    return try {
        val array = JSONArray(sourceAppsJson)
        (0 until array.length()).map { array.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}
