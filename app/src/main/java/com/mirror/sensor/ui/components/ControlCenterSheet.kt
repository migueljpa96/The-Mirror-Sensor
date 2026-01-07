package com.mirror.sensor.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.viewmodel.ControlCenterViewModel
import kotlin.random.Random

@Composable
fun ControlCenterSheet(
    isServiceRunning: Boolean,
    onToggleService: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: ControlCenterViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.startSensors()
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.stopSensors()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.startSensors()
        onDispose {
            viewModel.stopSensors()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val audioLevel by viewModel.audioLevel.collectAsState()
    val motionLevel by viewModel.motionLevel.collectAsState()
    val health by viewModel.health.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Handle
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 1. STATUS ORB
        StatusOrb(
            isActive = isServiceRunning,
            onClick = onToggleService
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isServiceRunning) "SYSTEM ACTIVE" else "SYSTEM PAUSED",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        // 2. SENSOR STRIPS
        SensorStrip(
            label = "AUDIO INPUT",
            isActive = health.micPermission,
            value = audioLevel
        )

        Spacer(modifier = Modifier.height(16.dp))

        SensorStrip(
            label = "INERTIAL SENSORS",
            isActive = health.physicalPermission,
            value = motionLevel,
            isMotion = true
        )

        Spacer(modifier = Modifier.height(48.dp))

        // 3. PERMISSION CHECKLIST
        PermissionList(health) { type ->
            when(type) {
                "USAGE" -> {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                else -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", context.packageName, null)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ... StatusOrb, SensorStrip, AudioVisualizerStrip, MotionVisualizer remain unchanged ...
@Composable
fun StatusOrb(isActive: Boolean, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "scale"
    )

    val color by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "color"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                            if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.DarkGray
                        )
                    )
                )
        )
    }
}

@Composable
fun SensorStrip(label: String, isActive: Boolean, value: Float, isMotion: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(40.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(100.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            if (isActive) {
                if (isMotion) {
                    MotionVisualizer(value)
                } else {
                    AudioVisualizerStrip(value)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("OFFLINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun AudioVisualizerStrip(amplitude: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val barCount = 30
        val barWidth = size.width / barCount
        val centerY = size.height / 2

        for (i in 0 until barCount) {
            val randomHeight = (amplitude * size.height * 0.8f * Random.nextFloat()).coerceAtLeast(2f)
            val x = i * barWidth

            drawLine(
                color = Color(0xFF00E676).copy(alpha = 0.8f),
                start = Offset(x, centerY - randomHeight / 2),
                end = Offset(x, centerY + randomHeight / 2),
                strokeWidth = barWidth * 0.6f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun MotionVisualizer(intensity: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "shake")
    val offsetX by infiniteTransition.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(50), RepeatMode.Reverse), label = "offset"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerY = size.height / 2
        drawLine(
            color = Color(0xFF2979FF).copy(alpha = 0.8f),
            start = Offset(0f, centerY + (if (intensity > 0.1f) offsetX * intensity * 10 else 0f)),
            end = Offset(size.width, centerY + (if (intensity > 0.1f) -offsetX * intensity * 10 else 0f)),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun PermissionList(health: com.mirror.sensor.viewmodel.SystemHealth, onFix: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PermissionItem(
            label = "Status Notifications",
            status = if (health.notificationPermission) "Active: Alerts enabled" else "Tap to enable notifications",
            isOk = health.notificationPermission
        ) { onFix("NOTIF") }

        PermissionItem(
            label = "Microphone",
            status = if (health.micPermission) "Active: Recording enabled" else "Tap to grant access",
            isOk = health.micPermission
        ) { onFix("MIC") }

        PermissionItem(
            label = "App Usage",
            status = if (health.usageStatsPermission) "Active: Tracking enabled" else "Tap to enable usage stats",
            isOk = health.usageStatsPermission
        ) { onFix("USAGE") }

        PermissionItem(
            label = "Motion Sensors",
            status = if (health.physicalPermission) "Active: Sensors linked" else "Tap to sync sensors",
            isOk = health.physicalPermission
        ) { onFix("BIO") }

        PermissionItem(
            label = "Location",
            status = if (health.locationPermission) "Active: GPS enabled" else "Tap to grant access",
            isOk = health.locationPermission
        ) { onFix("LOC") }
    }
}

@Composable
fun PermissionItem(label: String, status: String, isOk: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!isOk) onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (isOk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontSize = 11.sp
            )
        }

        Icon(
            imageVector = if (isOk) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
    }
}