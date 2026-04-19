package com.focal.ui.all

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.ui.digest.CategorySection
import com.focal.ui.theme.DigestBlue
import com.focal.ui.theme.DigestBlueContainer
import com.focal.ui.theme.NoiseSurface

@Composable
fun AllNotificationsScreen(
    viewModel: AllNotificationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "All Notifications",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        item {
            Text(
                text = "Last 24h \u00B7 ${state.totalCount} notifications",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        if (state.matters.isNotEmpty()) {
            item {
                CategorySection(
                    label = "MATTERS",
                    count = state.matters.size,
                    color = DigestBlue,
                    containerColor = DigestBlueContainer,
                    notifications = state.matters,
                    initiallyExpanded = true
                )
            }
        }

        if (state.noise.isNotEmpty()) {
            item {
                CategorySection(
                    label = "NOISE",
                    count = state.noise.size,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    containerColor = NoiseSurface,
                    notifications = state.noise,
                    initiallyExpanded = false
                )
            }
        }

        if (state.uncategorized.isNotEmpty()) {
            item {
                CategorySection(
                    label = "UNCLASSIFIED",
                    count = state.uncategorized.size,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    notifications = state.uncategorized,
                    initiallyExpanded = true
                )
            }
        }

        if (state.totalCount == 0) {
            item {
                Text(
                    text = "No notifications yet. Make sure notification access is enabled in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 32.dp)
                )
            }
        }
    }
}
