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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.focal.ui.theme.FocalAccent
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

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

            // Explanation cards row
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExplanationCard(
                        icon = "\uD83D\uDD12",
                        title = "Matters",
                        description = "Always surface. You said so.",
                        modifier = Modifier.weight(1f)
                    )
                    ExplanationCard(
                        icon = "\u2728",
                        title = "Auto",
                        description = "Let Focal decide what\u2019s worth telling you.",
                        modifier = Modifier.weight(1f)
                    )
                    ExplanationCard(
                        icon = "\uD83D\uDD07",
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
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Snackbar
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
private fun ExplanationCard(
    icon: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(IntrinsicSize.Max)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = icon,
                fontSize = 20.sp
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                text = if (app.isUserSet) "Set by you" else "system default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // M/A/N pill toggle
        MaNPill(
            selected = when (app.userOverride) {
                "matters" -> 0
                "noise" -> 2
                else -> 1
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

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
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
                    index == 1 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) // A: darker fill
                    else -> FocalAccent // M or N when user-set
                }
                val textColor = when {
                    !isSelected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    index == 1 -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.surface // contrast text on accent
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .clickable { onSelect(index) },
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
