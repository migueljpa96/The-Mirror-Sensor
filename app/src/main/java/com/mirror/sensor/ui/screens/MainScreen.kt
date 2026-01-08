package com.mirror.sensor.ui.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mirror.sensor.ui.components.ControlCenterSheet
import com.mirror.sensor.viewmodel.HomeViewModel
import com.mirror.sensor.viewmodel.MainViewModel
import com.mirror.sensor.viewmodel.OracleViewModel

// Helper for safe Activity lookup
fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onToggleService: (Boolean) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    // 1. ONBOARDING GATE
    val hasCompletedOnboarding by viewModel.hasCompletedOnboarding.collectAsState()

    if (!hasCompletedOnboarding) {
        OnboardingScreen(onComplete = { viewModel.setOnboardingComplete() })
        return
    }

    // 2. MAIN UI
    val navController = rememberNavController()
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    // Safe Activity Lookup for shared ViewModel scope
    val activityOwner = context.findComponentActivity()
        ?: throw IllegalStateException("MainScreen must be hosted in a ComponentActivity")

    val topLevelRoutes = listOf("Stream", "Reflection", "Oracle")
    val icons = listOf(
        Icons.Default.ViewStream,
        Icons.Default.AutoGraph,
        Icons.AutoMirrored.Filled.Chat // FIXED: Use AutoMirrored version
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "Stream"
    var showControlCenter by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentRoute.uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    if (currentRoute == "Stream") {
                        IconButton(onClick = { navController.navigate("Recall") }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showControlCenter = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Sensor Status",
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleService(isRunning)
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = "Toggle Recording"
                        )
                    }
                }
            )
        },
        bottomBar = {
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "Stream",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("Stream") {
                HomeScreen(
                    viewModel = viewModel(viewModelStoreOwner = activityOwner),
                    isServiceRunning = isRunning,
                    onOpenControlCenter = { showControlCenter = true },
                    onMemoryClick = { id -> navController.navigate("MemoryDetail/$id") }
                )
            }

            composable("Reflection") {
                ReflectionScreen(
                    homeViewModel = viewModel(viewModelStoreOwner = activityOwner)
                )
            }

            composable("Oracle") {
                OracleScreen(
                    homeViewModel = viewModel(viewModelStoreOwner = activityOwner),
                    oracleViewModel = viewModel()
                )
            }

            composable("Recall") {
                RecallScreen(
                    onBack = { navController.popBackStack() },
                    onMemoryClick = { id -> navController.navigate("MemoryDetail/$id") }
                )
            }

            composable("MemoryDetail/{memoryId}") { backStackEntry ->
                val id = backStackEntry.arguments?.getString("memoryId") ?: return@composable
                MemoryDetailScreen(
                    memoryId = id,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel(viewModelStoreOwner = activityOwner)
                )
            }
        }

        if (showControlCenter) {
            ModalBottomSheet(
                onDismissRequest = { showControlCenter = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                ControlCenterSheet(
                    isServiceRunning = isRunning,
                    onToggleService = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleService(isRunning)
                    },
                    onDismiss = { showControlCenter = false }
                )
            }
        }
    }
}