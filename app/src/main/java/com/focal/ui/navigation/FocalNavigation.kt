package com.focal.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
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
import com.focal.ui.pulse.PulseDetailScreen
import com.focal.ui.pulse.PulseScreen
import com.focal.ui.setup.SetupScreen
import com.focal.ui.tune.TuneScreen

val bottomNavItems = listOf(Screen.Digest, Screen.Pulse, Screen.All, Screen.Settings)

@Composable
fun FocalNavigation(widthSizeClass: WindowWidthSizeClass? = null) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val useRail = widthSizeClass != null && widthSizeClass >= WindowWidthSizeClass.Medium

    Scaffold(
        bottomBar = {
            if (!useRail) {
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
        }
    ) { innerPadding ->
        Row(modifier = Modifier.padding(innerPadding)) {
            if (useRail) {
                NavigationRail {
                    bottomNavItems.forEach { screen ->
                        NavigationRailItem(
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
        NavHost(
            navController = navController,
            startDestination = Screen.Digest.route,
            modifier = Modifier.weight(1f),
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            composable(Screen.Digest.route) {
                DigestScreen(
                    onTopicClick = { topicId ->
                        navController.navigate(Screen.TopicDetail.createRoute(topicId))
                    }
                )
            }
            composable(Screen.Pulse.route) {
                PulseScreen(
                    onWidgetClick = { widgetId ->
                        navController.navigate(Screen.PulseDetail.createRoute(widgetId))
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
            composable(
                Screen.Setup.route,
                enterTransition = {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300))
                },
                popExitTransition = {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300))
                }
            ) {
                SetupScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.TopicDetail.route,
                arguments = listOf(navArgument("topicId") { type = NavType.StringType }),
                enterTransition = {
                    scaleIn(initialScale = 0.85f, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
                },
                exitTransition = {
                    scaleOut(targetScale = 1.1f, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                },
                popEnterTransition = {
                    scaleIn(initialScale = 1.1f, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
                },
                popExitTransition = {
                    scaleOut(targetScale = 0.85f, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                }
            ) {
                TopicDetailScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.PulseDetail.route,
                arguments = listOf(navArgument("widgetId") { type = NavType.StringType }),
                enterTransition = {
                    scaleIn(initialScale = 0.85f, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
                },
                exitTransition = {
                    scaleOut(targetScale = 1.1f, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                },
                popEnterTransition = {
                    scaleIn(initialScale = 1.1f, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300))
                },
                popExitTransition = {
                    scaleOut(targetScale = 0.85f, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                }
            ) {
                PulseDetailScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
        }
    }
}
