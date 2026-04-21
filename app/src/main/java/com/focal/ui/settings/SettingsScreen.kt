package com.focal.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.intelligence.EmbeddingModelType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToSetup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshEmbeddingState()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                BackendToggleRow(
                    useGpu = state.useGpu,
                    restarting = state.engineRestarting,
                    onSelect = { viewModel.setBackendPreference(it) }
                )
            }

            item {
                EmbeddingEngineRow(
                    isModelAvailable = state.isEmbeddingModelAvailable,
                    isReady = state.isEmbeddingReady,
                    isInitializing = state.isEmbeddingInitializing,
                    onInitialize = { viewModel.initializeEmbedding() }
                )
            }

            item {
                EmbeddingModelRow(
                    activeModel = state.activeEmbeddingModel,
                    isGemmaAvailable = state.isGemmaAvailable,
                    isSwitching = state.isSwitchingEmbeddingModel,
                    onSelect = { viewModel.switchEmbeddingModel(it) }
                )
            }

            item {
                SetupNavRow(onClick = onNavigateToSetup)
            }

            item {
                Text(
                    text = "Choose how each app is classified",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            if (state.apps.isEmpty()) {
                item {
                    Text(
                        text = "No apps seen yet. Notifications will appear here as they arrive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                items(state.apps, key = { it.packageName }) { app ->
                    AppOverrideRow(
                        app = app,
                        onToggle = { newState -> viewModel.onToggle(app.packageName, newState) }
                    )
                }
            }
        }

        state.snackbarMessage?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = message)
            }
        }
    }
}

@Composable
private fun SetupNavRow(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Setup AI Engine",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Configure model & permissions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendToggleRow(
    useGpu: Boolean,
    restarting: Boolean,
    onSelect: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Inference Backend",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (restarting) "Restarting engine…" else "GPU is faster on supported devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("GPU", "CPU").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = if (index == 0) useGpu else !useGpu,
                        onClick = { if (!restarting) onSelect(index == 0) },
                        enabled = !restarting,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbeddingEngineRow(
    isModelAvailable: Boolean,
    isReady: Boolean,
    isInitializing: Boolean,
    onInitialize: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Embedding Engine",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = when {
                        !isModelAvailable -> "Model not downloaded"
                        isInitializing -> "Starting…"
                        isReady -> "Active"
                        else -> "Not started"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            if (isModelAvailable && !isInitializing) {
                Button(onClick = onInitialize) {
                    Text(if (isReady) "Restart" else "Start")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddingModelRow(
    activeModel: EmbeddingModelType,
    isGemmaAvailable: Boolean,
    isSwitching: Boolean,
    onSelect: (EmbeddingModelType) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Embedding Model",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = activeModel == EmbeddingModelType.GECKO,
                    onClick = { if (!isSwitching) onSelect(EmbeddingModelType.GECKO) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !isSwitching
                ) {
                    Text("Gecko 110M")
                }
                SegmentedButton(
                    selected = activeModel == EmbeddingModelType.GEMMA,
                    onClick = { if (!isSwitching && isGemmaAvailable) onSelect(EmbeddingModelType.GEMMA) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !isSwitching && isGemmaAvailable
                ) {
                    Text(if (isGemmaAvailable) "Gemma 300M" else "Gemma 300M\n(not downloaded)")
                }
            }
            if (isSwitching) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Switching model · re-embedding notifications…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppOverrideRow(
    app: AppOverride,
    onToggle: (String?) -> Unit
) {
    val options = listOf("Matters", "Auto", "Noise")
    val selectedIndex = when (app.userOverride) {
        "matters" -> 0
        null -> 1
        "noise" -> 2
        else -> 1
    }

    val systemHint = when (app.systemDefault) {
        "matters" -> "system: matters"
        "noise" -> "system: noise"
        else -> "system: llm"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${app.notificationCount} notifications · $systemHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == selectedIndex,
                        onClick = {
                            val newState = when (index) {
                                0 -> "matters"
                                2 -> "noise"
                                else -> null
                            }
                            onToggle(newState)
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        )
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
