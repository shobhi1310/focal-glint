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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestScreen(
    onTopicClick: (String) -> Unit = {},
    viewModel: DigestViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val needAttention = state.urgentCount + state.actionableCount

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
            item {
                Text(
                    text = "Your Digest",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            item {
                Text(
                    text = buildString {
                        append("Last 24h")
                        append(" \u00B7 ${state.topics.size} topics")
                        if (needAttention > 0) {
                            append(" \u00B7 $needAttention need attention")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            if (state.topics.isEmpty() && state.totalNotifications == 0) {
                item {
                    Text(
                        text = "No notifications yet. Make sure notification access is enabled in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else if (state.topics.isEmpty()) {
                item {
                    Text(
                        text = "Processing notifications into topics...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                items(state.topics, key = { it.id }) { topic ->
                    TopicCard(
                        topic = topic,
                        onClick = { onTopicClick(topic.id) }
                    )
                }
            }

            if (state.noiseCount > 0) {
                item {
                    Text(
                        text = "${state.noiseCount} noise notifications hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
