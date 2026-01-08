package com.mirror.sensor.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
// FIXED IMPORT:
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.ui.components.LiveMemoryCard
import com.mirror.sensor.viewmodel.OnboardingStep
import com.mirror.sensor.viewmodel.OnboardingViewModel
import kotlin.math.roundToInt

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: OnboardingViewModel = viewModel()) {
    val step by viewModel.currentStep.collectAsState()

    // No change needed here, just the import above
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.checkSensors() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val drillComplete by viewModel.drillComplete.collectAsState()
    LaunchedEffect(drillComplete) { if (drillComplete) onComplete() }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        AnimatedContent(targetState = step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "Onboarding") { currentStep ->
            when (currentStep) {
                OnboardingStep.TRANSPARENCY -> TransparencyStep(viewModel)
                OnboardingStep.SENSORS -> SensorGrantStep(viewModel)
                OnboardingStep.DRILL -> DrillStep(viewModel)
            }
        }
    }
}

// ... (Rest of file remains unchanged: SensorGrantStep, TransparencyStep, DrillStep, etc.)
@Composable
fun SensorGrantStep(viewModel: OnboardingViewModel) {
    val sensorState by viewModel.sensorState.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()

    fun handlePermissionClick(permission: String, isGranted: Boolean, launcher: androidx.activity.result.ActivityResultLauncher<String>) {
        if (isGranted) return
        if (activity != null) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            val hasAskedBefore = viewModel.hasAskedPermission(permission)
            if (shouldShowRationale) {
                launcher.launch(permission)
                viewModel.markPermissionRequested(permission)
            } else {
                if (hasAskedBefore) viewModel.openAppSettings(context)
                else {
                    launcher.launch(permission)
                    viewModel.markPermissionRequested(permission)
                }
            }
        } else launcher.launch(permission)
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.checkSensors() }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.checkSensors() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.checkSensors() }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        IconButton(onClick = { viewModel.navigateBack() }, modifier = Modifier.offset(x = (-12).dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Access Tokens", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Grant capability tokens for this session.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(32.dp))

        SensorCable("Transparency", if (sensorState.hasNotifications) "Active: Transparency enabled." else "Required to show active status.", Icons.Default.Notifications, sensorState.hasNotifications) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) handlePermissionClick(Manifest.permission.POST_NOTIFICATIONS, sensorState.hasNotifications, notifLauncher)
        }
        Spacer(modifier = Modifier.height(16.dp))
        SensorCable("Session Audio", if (sensorState.hasMic) "Active: Microphone ready." else "Tap to grant microphone access.", Icons.Default.Mic, sensorState.hasMic) {
            handlePermissionClick(Manifest.permission.RECORD_AUDIO, sensorState.hasMic, micLauncher)
        }
        Spacer(modifier = Modifier.height(16.dp))
        SensorCable("Spatial Context", if (sensorState.hasLocation) "Active: Location logging enabled." else "Tap to grant location access.", Icons.Default.LocationOn, sensorState.hasLocation) {
            handlePermissionClick(Manifest.permission.ACCESS_FINE_LOCATION, sensorState.hasLocation, locationLauncher)
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = { viewModel.moveToDrill() }, enabled = viewModel.areAllSensorsGranted(), modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Initialize First Session")
        }
    }
}

@Composable
fun TransparencyStep(viewModel: OnboardingViewModel) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(32.dp))
            Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Wisdom, Not Wiretaps.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(32.dp))
            PromiseItem(Icons.Default.VisibilityOff, "On Demand Only", "I only observe when you tap Start. Never in the background.")
            Spacer(modifier = Modifier.height(20.dp))
            PromiseItem(Icons.Default.DataUsage, "The 7-Day Rule", "Raw audio/logs are deleted after 7 days. Only insights remain.")
            Spacer(modifier = Modifier.height(20.dp))
            PromiseItem(Icons.Default.Lock, "Your Vault", "Data is encrypted and processed in a private cloud container. Never sold.")
        }
        Spacer(modifier = Modifier.height(48.dp))
        SwipeToSign(onSign = { viewModel.completeTransparency() })
    }
}

@Composable
fun DrillStep(viewModel: OnboardingViewModel) {
    val isRunning by viewModel.drillActive.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        IconButton(onClick = { viewModel.navigateBack() }, modifier = Modifier.offset(x = (-12).dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(if (isRunning) "SYSTEM LIVE" else "READY FOR CALIBRATION", style = MaterialTheme.typography.labelLarge, color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(32.dp))
            LiveMemoryCard(isRecording = isRunning, audioLevel = if (isRunning) 0.5f else 0f, onClick = {})
            Spacer(modifier = Modifier.height(48.dp))
            FilledIconButton(onClick = { viewModel.toggleDrill() }, modifier = Modifier.size(80.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(text = if (isRunning) "Pull down status bar to verify notification.\nThen tap STOP." else "Initialize your first session to complete setup.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun SensorCable(label: String, desc: String, icon: ImageVector, isConnected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val iconColor = if (isConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline
    val iconBg = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Row(modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(40.dp).background(iconBg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isConnected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun PromiseItem(icon: ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwipeToSign(onSign: () -> Unit) {
    val width = 300.dp
    val height = 56.dp
    val thumbSize = 48.dp
    val padding = 4.dp
    val density = LocalDensity.current
    val totalWidthPx = with(density) { width.toPx() }
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val paddingPx = with(density) { padding.toPx() }
    val maxOffset = totalWidthPx - thumbSizePx - (paddingPx * 2)
    var offsetX by remember { mutableFloatStateOf(0f) }
    var isSigned by remember { mutableStateOf(false) }
    Box(modifier = Modifier.width(width).height(height).clip(RoundedCornerShape(100)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(100)), contentAlignment = Alignment.CenterStart) {
        Text("Slide to Accept Protocol", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        Box(modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }.padding(padding).size(thumbSize).clip(CircleShape).background(if (isSigned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer).draggable(orientation = Orientation.Horizontal, state = rememberDraggableState { delta -> if (!isSigned) offsetX = (offsetX + delta).coerceIn(0f, maxOffset) }, onDragStopped = { if (offsetX >= maxOffset * 0.9f) { offsetX = maxOffset; isSigned = true; onSign() } else offsetX = 0f }), contentAlignment = Alignment.Center) {
            Icon(imageVector = if (isSigned) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = if (isSigned) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}