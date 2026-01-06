package com.mirror.sensor.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.ui.components.DateSelector
import com.mirror.sensor.ui.components.MemoryCard
import com.mirror.sensor.ui.components.TimelineItem
import com.mirror.sensor.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    isServiceRunning: Boolean = false,
    onMemoryClick: (String) -> Unit
) {
    val memories by viewModel.memories.collectAsState()
    val liveAmplitude by viewModel.audioLevel.collectAsState()

    var selectedDate by remember { mutableStateOf(Date()) }

    val filteredMemories = remember(memories, selectedDate) {
        memories.filter { memory ->
            val memDate = memory.anchor_date?.toDate()
            memDate != null && isSameDay(memDate, selectedDate)
        }.sortedByDescending { it.anchor_date?.toDate() }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // 1. STATUS CARD (Top Element)
        RecordingStatusCard(
            isRecording = isServiceRunning,
            amplitude = if (isServiceRunning) liveAmplitude else 0f
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 2. HEADER
            item {
                Text(
                    "Your Stream",
                    style = MaterialTheme.typography.headlineSmall, // Scaled down slightly for elegance
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }

            // 3. DATE SELECTOR
            item {
                DateSelector(
                    selectedDate = selectedDate,
                    onDateSelected = { selectedDate = it }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 4. TIMELINE
            if (filteredMemories.isEmpty()) {
                item { EmptyStateView(selectedDate) }
            } else {
                itemsIndexed(filteredMemories) { index, memory ->
                    TimelineItem(
                        memory = memory,
                        isLast = index == filteredMemories.lastIndex
                    ) {
                        MemoryCard(
                            memory = memory,
                            onClick = { onMemoryClick(memory.id) }
                        )
                    }
                }
            }
        }
    }
}

// --- VISUAL COMPONENTS ---

@Composable
fun RecordingStatusCard(isRecording: Boolean, amplitude: Float) {
    val containerColor = if (isRecording) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isRecording) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val statusText = if (isRecording) "System Active" else "System Paused" // Changed text for "Tech" feel

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp) // Flat look
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // LEFT: Status Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecording) {
                    PulsingRedDot()
                } else {
                    Icon(Icons.Default.Pause, null, tint = contentColor.copy(alpha = 0.5f), modifier = Modifier.size(10.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    if (isRecording) {
                        Text(
                            text = "Monitoring environment...",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // RIGHT: Audio Visualizer
            if (isRecording) {
                AudioVisualizer(amplitude = amplitude, color = contentColor)
            }
        }
    }
}

@Composable
fun PulsingRedDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha"
    )
    Icon(
        imageVector = Icons.Default.FiberManualRecord,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error.copy(alpha = alpha),
        modifier = Modifier.size(12.dp)
    )
}

@Composable
fun AudioVisualizer(amplitude: Float, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp), // Tighter bars
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp)
    ) {
        VisualizerBar(amplitude, 0.5f, color)
        VisualizerBar(amplitude, 0.8f, color)
        VisualizerBar(amplitude, 1.0f, color)
        VisualizerBar(amplitude, 0.8f, color)
        VisualizerBar(amplitude, 0.5f, color)
    }
}

@Composable
fun VisualizerBar(amplitude: Float, scaleFactor: Float, color: Color) {
    val targetHeight = (6.dp + (24.dp * amplitude * scaleFactor)).value.coerceIn(4f, 20f)
    val height by animateDpAsState(
        targetValue = targetHeight.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "bar"
    )

    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.8f))
    )
}

private fun isSameDay(d1: Date, d2: Date): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return fmt.format(d1) == fmt.format(d2)
}

@Composable
fun EmptyStateView(date: Date) {
    val isToday = isSameDay(date, Date())
    val message = if (isToday) "No data intercepted yet." else "No archives found."

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.ViewStream,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}