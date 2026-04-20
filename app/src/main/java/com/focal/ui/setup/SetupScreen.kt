package com.focal.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.focal.intelligence.ModelVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showClearDatabaseDialog by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    if (showClearDatabaseDialog) {
        AlertDialog(
            onDismissRequest = { showClearDatabaseDialog = false },
            title = { Text("Clear all database data?") },
            text = {
                Text("This deletes notifications and other Room-backed app data, but keeps models, preferences, and downloaded files.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDatabaseDialog = false
                        viewModel.onClearDatabase()
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDatabaseDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StepCard(
                    number = 1,
                    title = "Notification Access",
                    description = "Allow Focal to read notifications from all apps",
                    isComplete = state.notificationAccessGranted
                ) {
                    if (!state.notificationAccessGranted) {
                        Button(onClick = viewModel::onOpenNotifSettings) {
                            Text("Open Settings")
                        }
                    }
                }
            }

            item {
                StepCard(
                    number = 2,
                    title = "Select Model",
                    description = "Choose the AI model for your device",
                    isComplete = true
                ) {
                    Column {
                        ModelVariant.entries.forEach { variant ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onModelSelected(variant) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.selectedModel == variant,
                                    onClick = { viewModel.onModelSelected(variant) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = variant.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = buildString {
                                            append(variant.sizeLabel)
                                            if (variant in state.modelsOnDevice) append(" · on device")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                val modelAvailable = state.activeModel == state.selectedModel
                val isDownloading = state.downloadProgress != null

                StepCard(
                    number = 3,
                    title = "Download Model",
                    description = "Download the selected model to your device",
                    isComplete = modelAvailable
                ) {
                    Column {
                        Button(
                            onClick = viewModel::onDownload,
                            enabled = !modelAvailable && !isDownloading
                        ) {
                            Text(
                                text = when {
                                    isDownloading -> "Downloading ${state.downloadProgress}%"
                                    modelAvailable -> "Downloaded ✓"
                                    else -> "Download"
                                }
                            )
                        }
                        if (modelAvailable && !isDownloading) {
                            TextButton(onClick = viewModel::onRedownload) {
                                Text(
                                    text = "Re-download",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                        state.errorMessage?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                StepCard(
                    number = 4,
                    title = "Backend",
                    description = "Choose GPU (faster) or CPU (more compatible) for model inference",
                    isComplete = true
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.onToggleBackend(true) },
                            enabled = !state.backendSwitching && !state.useGpu
                        ) {
                            Text(if (state.useGpu) "GPU ✓" else "GPU")
                        }
                        Button(
                            onClick = { viewModel.onToggleBackend(false) },
                            enabled = !state.backendSwitching && state.useGpu
                        ) {
                            Text(if (!state.useGpu) "CPU ✓" else "CPU")
                        }
                    }
                    if (state.backendSwitching) {
                        Text(
                            text = "Switching backend...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            item {
                val modelAvailable = state.activeModel == state.selectedModel
                val isDownloading = state.downloadProgress != null

                StepCard(
                    number = 5,
                    title = "Start Engine",
                    description = "Load the AI model into memory for classification",
                    isComplete = state.engineRunning
                ) {
                    Button(
                        onClick = if (state.engineRunning) viewModel::onStopEngine else viewModel::onStartEngine,
                        enabled = modelAvailable && !isDownloading && !state.engineStopping
                    ) {
                        Text(when {
                            state.engineStopping -> "Stopping…"
                            state.engineRunning -> "Stop"
                            else -> "Start"
                        })
                    }
                }
            }

            item {
                val isDownloading = state.embeddingDownloadProgress != null

                StepCard(
                    number = 6,
                    title = "Embedding Model",
                    description = "Download the Gecko embedding model for smart notification grouping (~30 MB)",
                    isComplete = state.embeddingModelAvailable
                ) {
                    Column {
                        Button(
                            onClick = viewModel::onDownloadEmbeddingModel,
                            enabled = !state.embeddingModelAvailable && !isDownloading
                        ) {
                            Text(
                                text = when {
                                    isDownloading -> "Downloading ${state.embeddingDownloadProgress}%"
                                    state.embeddingModelAvailable -> "Downloaded ✓"
                                    else -> "Download"
                                }
                            )
                        }
                        state.embeddingErrorMessage?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                StepCard(
                    number = 7,
                    title = "Developer Reset",
                    description = "Delete all Room database data and start fresh without clearing app data",
                    isComplete = false
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showClearDatabaseDialog = true },
                            enabled = !state.databaseClearing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(if (state.databaseClearing) "Clearing..." else "Clear Database")
                        }
                        state.databaseMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (message.startsWith("Failed"))
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    description: String,
    isComplete: Boolean,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Step $number: $title",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isComplete) "✓" else "PENDING",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isComplete)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
