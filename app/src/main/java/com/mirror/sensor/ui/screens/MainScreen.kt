package com.mirror.sensor.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search // <--- NEW IMPORT
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onToggleService: (Boolean) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val navController = rememberNavController()
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val haptic = LocalHapticFeedback.current

    val topLevelRoutes = listOf("Stream", "Patterns", "Oracle")
    val items = topLevelRoutes
    val icons = listOf(Icons.Default.ViewStream, Icons.Default.AutoGraph, Icons.Default.Chat)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "Stream"

    // Hide global bars for Detail AND Recall screens
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
                        // 1. SEARCH ACTION (Only on Stream)
                        if (currentRoute == "Stream") {
                            IconButton(onClick = { navController.navigate("Recall") }) {
                                Icon(Icons.Default.Search, contentDescription = "Search Memories")
                            }
                        }

                        // 2. TOGGLE SERVICE ACTION
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
                NavigationBar {
                    items.forEachIndexed { index, screen ->
                        NavigationBarItem(
                            icon = { Icon(icons[index], contentDescription = screen) },
                            label = { Text(screen) },
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
            composable("Stream") {
                HomeScreen(
                    isServiceRunning = isRunning,
                    onMemoryClick = { memoryId ->
                        navController.navigate("MemoryDetail/$memoryId")
                    }
                )
            }
            composable("Patterns") { PatternsScreen() }
            composable("Oracle") { OracleScreen() }

            // NEW: RECALL SCREEN
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