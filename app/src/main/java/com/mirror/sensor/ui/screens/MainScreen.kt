package com.mirror.sensor.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mirror.sensor.viewmodel.MainViewModel
import com.mirror.sensor.viewmodel.SystemDashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onToggleService: (Boolean) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val navController = rememberNavController()
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val haptic = LocalHapticFeedback.current

    // UPDATED NAVIGATION: 4 Tabs
    val topLevelRoutes = listOf("Stream", "Dashboard", "Patterns", "Oracle")
    val icons = listOf(
        Icons.Default.ViewStream,
        Icons.Default.Dashboard,
        Icons.Default.AutoGraph, // Patterns maintained
        Icons.Default.Chat
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "Stream"

    val showGlobalBars = currentRoute in topLevelRoutes || currentRoute == "Stream"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),

        topBar = {
            if (showGlobalBars) {
                TopAppBar(
                    title = {
                        Text(
                            currentRoute,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    actions = {
                        if (currentRoute == "Stream") {
                            IconButton(onClick = { navController.navigate("Recall") }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }

                        // Toggle Service Button
                        FilledIconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleService(isRunning)
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.padding(end = 12.dp, start = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = "Toggle Service"
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showGlobalBars) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    topLevelRoutes.forEachIndexed { index, screen ->
                        NavigationBarItem(
                            icon = { Icon(icons[index], contentDescription = screen) },
                            label = { Text(screen, style = MaterialTheme.typography.labelSmall) },
                            selected = currentRoute == screen,
                            onClick = {
                                navController.navigate(screen) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
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
        NavHost(
            navController = navController,
            startDestination = "Stream",
            modifier = Modifier.padding(innerPadding)
        ) {
            // NEW DASHBOARD SCREEN
            composable("Dashboard") {
                SystemDashboardScreen(
                    isServiceRunning = isRunning,
                    onToggleService = { onToggleService(isRunning) }
                )
            }

            composable("Stream") {
                HomeScreen(
                    isServiceRunning = isRunning,
                    onMemoryClick = { memoryId ->
                        navController.navigate("MemoryDetail/$memoryId")
                    }
                )
            }

            // PATTERNS (Maintained)
            composable("Patterns") { PatternsScreen() }

            composable("Oracle") { OracleScreen() }

            composable("Recall") {
                RecallScreen(
                    onBack = { navController.popBackStack() },
                    onMemoryClick = { memoryId ->
                        navController.navigate("MemoryDetail/$memoryId")
                    }
                )
            }

            composable("MemoryDetail/{memoryId}") { backStackEntry ->
                val memoryId = backStackEntry.arguments?.getString("memoryId") ?: return@composable
                MemoryDetailScreen(
                    memoryId = memoryId,
                    onBack = { navController.popBackStack() },
                    viewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                        viewModelStoreOwner = (navController.context as androidx.activity.ComponentActivity)
                    )
                )
            }
        }
    }
}