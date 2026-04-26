package com.focal.ui.digest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestScreen(
    onTopicClick: (String) -> Unit = {},
    viewModel: DigestViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = state.isProcessing,
        onRefresh = { viewModel.onRefresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Day/time label
            item {
                Text(
                    text = state.dayTimeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Greeting with name in accent color
            item {
                val parts = state.greeting.split(", ", limit = 2)
                val annotated = buildAnnotatedString {
                    append(parts.getOrElse(0) { "" })
                    if (parts.size > 1) {
                        append(", ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append(parts[1])
                        }
                    }
                }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Stats line
            item {
                val statsText = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("${state.mattersCount} matters")
                    }
                    append(" · ${state.noiseCount} noise · ${state.totalNotifications} total")
                }
                Text(
                    text = statsText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Section header
            item {
                SectionHeader(title = "MATTERS TO YOU", count = state.mattersCount)
            }

            if (state.isProcessing) {
                item {
                    Text(
                        text = "Refreshing your digest...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                if (state.stories.isEmpty() && state.totalNotifications == 0) {
                    item {
                        Text(
                            text = "No notifications yet. Make sure notification access is enabled in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 32.dp)
                        )
                    }
                } else if (state.stories.isEmpty()) {
                    item {
                        Text(
                            text = "Processing notifications into stories...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 32.dp)
                        )
                    }
                } else {
                    items(state.stories, key = { it.id }) { topic ->
                        TopicCard(
                            topic = topic,
                            onClick = { onTopicClick(topic.id) }
                        )
                    }
                }
            }
        }
    }
}
