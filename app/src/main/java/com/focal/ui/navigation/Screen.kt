package com.focal.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Digest : Screen("digest", "Digest", Icons.Default.Star)
    data object All : Screen("all", "All", Icons.AutoMirrored.Filled.FormatListBulleted)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object Setup : Screen("setup", "Setup", Icons.Default.Settings)
    data object TopicDetail : Screen("topic/{topicId}", "Topic Detail", Icons.Default.Star) {
        fun createRoute(topicId: String) = "topic/$topicId"
    }
}
