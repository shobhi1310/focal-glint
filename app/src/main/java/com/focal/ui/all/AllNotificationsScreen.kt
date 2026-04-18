package com.focal.ui.all

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.ui.digest.CategorySection
import com.focal.ui.theme.ActionableAmber
import com.focal.ui.theme.ActionableAmberContainer
import com.focal.ui.theme.DigestBlue
import com.focal.ui.theme.DigestBlueContainer
import com.focal.ui.theme.NoiseSurface
import com.focal.ui.theme.UrgentRed
import com.focal.ui.theme.UrgentRedContainer

@Composable
fun AllNotificationsScreen(
    viewModel: AllNotificationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "All Notifications",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Last 24h \u00B7 ${state.totalCount} notifications",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (state.urgent.isNotEmpty()) {
            CategorySection(
                label = "URGENT",
                count = state.urgent.size,
                color = UrgentRed,
                containerColor = UrgentRedContainer,
                notifications = state.urgent,
                initiallyExpanded = true
            )
        }

        if (state.actionable.isNotEmpty()) {
            CategorySection(
                label = "ACTIONABLE",
                count = state.actionable.size,
                color = ActionableAmber,
                containerColor = ActionableAmberContainer,
                notifications = state.actionable,
                initiallyExpanded = true
            )
        }

        if (state.digest.isNotEmpty()) {
            CategorySection(
                label = "DIGEST",
                count = state.digest.size,
                color = DigestBlue,
                containerColor = DigestBlueContainer,
                notifications = state.digest,
                initiallyExpanded = true
            )
        }

        if (state.noise.isNotEmpty()) {
            CategorySection(
                label = "NOISE",
                count = state.noise.size,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                containerColor = NoiseSurface,
                notifications = state.noise,
                initiallyExpanded = false
            )
        }

        if (state.totalCount == 0) {
            Text(
                text = "No notifications yet. Make sure notification access is enabled in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 32.dp)
            )
        }
    }
}
