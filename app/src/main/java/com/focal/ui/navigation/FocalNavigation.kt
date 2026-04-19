package com.focal.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.focal.ui.all.AllNotificationsScreen
import com.focal.ui.digest.DigestScreen
import com.focal.ui.digest.TopicDetailScreen
import com.focal.ui.setup.SetupScreen
import com.focal.ui.tune.TuneScreen

val bottomNavItems = listOf(Screen.Digest, Screen.All, Screen.Settings)

@Composable
fun FocalNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Digest.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Digest.route) {
                DigestScreen(
                    onTopicClick = { topicId ->
                        navController.navigate(Screen.TopicDetail.createRoute(topicId))
                    }
                )
            }
            composable(Screen.All.route) {
                AllNotificationsScreen()
            }
            composable(Screen.Settings.route) {
                TuneScreen(
                    onNavigateToDevSettings = {
                        navController.navigate(Screen.Setup.route)
                    }
                )
            }
            composable(Screen.Setup.route) {
                SetupScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.TopicDetail.route,
                arguments = listOf(navArgument("topicId") { type = NavType.StringType })
            ) {
                TopicDetailScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
