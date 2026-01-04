package com.mirror.sensor.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@Composable
fun MainScreen(
    onStartService: () -> Unit
) {
    val navController = rememberNavController()

    // Define the screens
    val items = listOf("Stream", "Patterns", "Oracle")
    val icons = listOf(
        Icons.Default.ViewStream,
        Icons.Default.AutoGraph,
        Icons.Default.Chat
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                items.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = screen) },
                        label = { Text(screen) },
                        selected = currentRoute == screen,
                        onClick = {
                            navController.navigate(screen) {
                                // Pop up to the start destination of the graph to
                                // avoid building up a large stack of destinations
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination when
                                // reselecting the same item
                                launchSingleTop = true
                                // Restore state when reselecting a previously selected item
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
            startDestination = "Stream",
            modifier = Modifier.padding(innerPadding)
        ) {
            // 1. THE STREAM (Existing)
            composable("Stream") {
                // We pass the start service callback down to Home
                HomeScreen(onStartService = onStartService)
            }

            // 2. THE PATTERNS (New)
            composable("Patterns") {
                PatternsScreen()
            }

            // 3. THE ORACLE (Placeholder for next phase)
            composable("Oracle") {
                // Temporary Placeholder
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text("The Oracle is sleeping...", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}