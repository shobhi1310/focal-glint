package com.focal.ui.tune

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.ui.components.AppIcon
import com.focal.ui.components.SectionHeader
import com.focal.ui.components.UserPreference
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.focal.ui.theme.ThemeMode
import com.focal.ui.theme.ThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneScreen(
    onNavigateToDevSettings: () -> Unit = {},
    viewModel: TuneViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            item {
                Text(
                    text = "Tune the noise.",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            item {
                Text(
                    text = "Three tiers. Pick by hand, or let the model learn from how you read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Name setting
            item {
                SectionHeader(title = "YOUR NAME")
            }
            item {
                val context = LocalContext.current
                var name by remember { mutableStateOf(UserPreference.getUserName(context)) }

                OutlinedTextField(
                    value = name,
                    onValueChange = { newName ->
                        name = newName
                        UserPreference.setUserName(context, newName)
                    },
                    placeholder = { Text("Enter your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Theme toggle
            item {
                SectionHeader(title = "APPEARANCE")
            }
            item {
                val themeMode by ThemePreference.themeMode.collectAsState()
                val context = LocalContext.current
                val options = listOf("System", "Light", "Dark")
                val selectedIndex = when (themeMode) {
                    ThemeMode.SYSTEM -> 0
                    ThemeMode.LIGHT -> 1
                    ThemeMode.DARK -> 2
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = index == selectedIndex,
                            onClick = {
                                val mode = when (index) {
                                    0 -> ThemeMode.SYSTEM
                                    1 -> ThemeMode.LIGHT
                                    else -> ThemeMode.DARK
                                }
                                ThemePreference.setThemeMode(context, mode)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Inference toggle
            item {
                SectionHeader(title = "INFERENCE")
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloud classification",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Use our hosted model for better notification classification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = state.cloudEnabled,
                        onCheckedChange = viewModel::onToggleCloud
                    )
                }
            }
            if (state.cloudEnabled) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Help improve Focal",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Allow anonymized notification data to be used for training.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.dataConsentEnabled,
                            onCheckedChange = viewModel::onToggleDataConsent
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // User focus
            item {
                SectionHeader(title = "YOUR FOCUS")
            }
            item {
                Text(
                    text = "Tell Focal what you care about. This shapes how notifications are triaged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                var focus by remember(state.userFocus) { mutableStateOf(state.userFocus) }
                val speechLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        val spoken = result.data
                            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull() ?: return@rememberLauncherForActivityResult
                        val updated = if (focus.isBlank()) spoken else "$focus $spoken"
                        focus = updated
                        viewModel.onUserFocusChanged(updated)
                    }
                }
                OutlinedTextField(
                    value = focus,
                    onValueChange = { focus = it; viewModel.onUserFocusChanged(it) },
                    placeholder = {
                        Text(
                            "e.g. Track my UPI spends from GPay and PhonePe. " +
                                "Show me when Shruti or Amma messages. " +
                                "Flag HDFC and ICICI bank alerts. " +
                                "Ignore all news and YouTube.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you want Focal to focus on?")
                            }
                            speechLauncher.launch(intent)
                        }) {
                            Icon(
                                Icons.Outlined.Mic,
                                contentDescription = "Speak",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Explanation cards row
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExplanationCard(
                        icon = { Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        title = "Matters",
                        description = "Always surface. You said so.",
                        modifier = Modifier.weight(1f)
                    )
                    ExplanationCard(
                        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        title = "Auto",
                        description = "Let Focal decide what\u2019s worth telling you.",
                        modifier = Modifier.weight(1f)
                    )
                    ExplanationCard(
                        icon = { Icon(Icons.AutoMirrored.Outlined.VolumeOff, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        title = "Noise",
                        description = "Silenced. Bundled into a footnote.",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // App list section header
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(title = "YOUR APPS", count = state.apps.size)
            }

            // App rows
            if (state.apps.isEmpty()) {
                item {
                    Text(
                        text = "No apps seen yet. Notifications will appear here as they arrive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                items(state.apps, key = { it.packageName }) { app ->
                    TuneAppRow(
                        app = app,
                        onToggle = { newState -> viewModel.onToggle(app.packageName, newState) }
                    )
                }
            }

            // Developer Settings link
            item {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onNavigateToDevSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Developer Settings",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Snackbar
        state.snackbarMessage?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(text = message)
            }
        }
    }
}

@Composable
private fun ExplanationCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(IntrinsicSize.Max)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            icon()
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun TuneAppRow(
    app: TuneAppItem,
    onToggle: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon (real icon from PackageManager, letter fallback)
        AppIcon(
            packageName = app.packageName,
            appName = app.appName,
            size = 36.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        // App name + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when {
                    app.userOverride == "auto" -> "Set to auto (was: ${app.systemDefault})"
                    app.isUserSet -> "Set by you"
                    app.systemDefault != null -> "Default: ${app.systemDefault}"
                    else -> "${app.notificationCount} notifications"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // M/A/N pill: if user has explicitly set, show their choice.
        // If no user override, show system default. Tapping A clears user override.
        MaNPill(
            selected = when (app.userOverride) {
                "matters" -> 0
                "noise" -> 2
                "auto" -> 1
                else -> when (app.systemDefault) {
                    "matters" -> 0
                    "noise" -> 2
                    else -> 1
                }
            },
            isUserSet = app.isUserSet,
            onSelect = { index ->
                when (index) {
                    0 -> onToggle("matters")
                    1 -> onToggle(null)
                    2 -> onToggle("noise")
                }
            }
        )
    }
}

@Composable
private fun MaNPill(
    selected: Int,
    isUserSet: Boolean,
    onSelect: (Int) -> Unit
) {
    val labels = listOf("M", "A", "N")
    val contentDescriptions = listOf("Matters", "Auto", "Noise")

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large
            )
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                val isSelected = index == selected
                val bgColor = when {
                    !isSelected -> MaterialTheme.colorScheme.surface
                    index == 1 -> MaterialTheme.colorScheme.surfaceContainerHigh // A: subtle fill
                    else -> MaterialTheme.colorScheme.primary // M or N when user-set
                }
                val textColor = when {
                    !isSelected -> MaterialTheme.colorScheme.outline
                    index == 1 -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onPrimary // contrast text on accent
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .clickable { onSelect(index) }
                        .semantics { contentDescription = contentDescriptions[index] },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = textColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
