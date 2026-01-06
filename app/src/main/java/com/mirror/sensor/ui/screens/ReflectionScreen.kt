package com.mirror.sensor.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.viewmodel.HomeViewModel
import com.mirror.sensor.viewmodel.ReflectionViewModel
import com.mirror.sensor.viewmodel.TimeSegment

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReflectionScreen(
    homeViewModel: HomeViewModel = viewModel(),
    reflectionViewModel: ReflectionViewModel = viewModel()
) {
    // 1. Sync Data from HomeViewModel (Source of Truth)
    val memories by homeViewModel.memories.collectAsState()
    LaunchedEffect(memories) {
        reflectionViewModel.analyze(memories)
    }

    val state by reflectionViewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. THE HERO: NARRATIVE ---
        item {
            Text(
                "DAILY BRIEFING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Icon(
                        Icons.Default.AutoGraph,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.narrative,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 32.sp
                    )
                }
            }
        }

        // --- 2. THE VITALS & CONTEXT (Middle Row) ---
        item {
            Row(
                modifier = Modifier.height(180.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // A. Bio-Gauges (Energy/Stress)
                Card(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("VITALS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        BioGauges(energy = state.avgEnergy, stress = state.avgStress)
                    }
                }

                // B. Time Distribution (Donut)
                Card(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("FOCUS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        ContextDonut(segments = state.timeDistribution)
                    }
                }
            }
        }

        // --- 3. THE MIND: TOPIC CLOUD ---
        item {
            Text(
                "HEADSPACE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.topTopics.forEach { topic ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = topic.lowercase(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- VISUALIZATIONS ---

@Composable
fun BioGauges(energy: Float, stress: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().height(100.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        VerticalGauge("Energy", energy, Color(0xFFFFD54F)) // Amber
        VerticalGauge("Stress", stress, if (stress > 0.6) Color(0xFFEF5350) else Color(0xFF66BB6A)) // Red/Green
    }
}

@Composable
fun VerticalGauge(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction = value.coerceIn(0.1f, 1f))
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
    }
}

@Composable
fun ContextDonut(segments: List<TimeSegment>) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(90.dp)) {
            val strokeWidth = 12.dp.toPx()
            var startAngle = -90f

            if (segments.isEmpty()) {
                drawCircle(
                    color = Color.LightGray.copy(alpha = 0.3f),
                    style = Stroke(width = strokeWidth)
                )
            } else {
                segments.forEach { segment ->
                    val sweep = (segment.hours / 100f) * 360f
                    drawArc(
                        color = Color(segment.colorHex),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                    startAngle += sweep
                }
            }
        }
        // Center Text
        if (segments.isNotEmpty()) {
            Text(
                text = "${segments.first().hours.toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // Legend for Top Item
    if (segments.isNotEmpty()) {
        Text(
            text = segments.first().label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}