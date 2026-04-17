package com.focal.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Digest : Screen("digest", "Digest", Icons.Default.Inbox)
    data object Apps : Screen("apps", "Apps", Icons.Default.Apps)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}
