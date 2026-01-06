package com.mirror.sensor.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.viewmodel.HomeViewModel
import com.mirror.sensor.viewmodel.PatternsViewModel

@Composable
fun PatternsScreen(
    homeViewModel: HomeViewModel = viewModel(),
    patternsViewModel: PatternsViewModel = viewModel()
) {
    val memories by homeViewModel.memories.collectAsState()
    LaunchedEffect(memories) {
        patternsViewModel.calculateStats(memories)
    }

    val stats by patternsViewModel.stats.collectAsState()

    // --- ANIMATION STATE ---
    // This value controls the "drawing" of the line chart (0f -> 1f)
    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(stats) {
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1500)
        )
    }

    // THEME & COLORS
    val energyColor = Color(0xFF00E5FF) // Cyan Neon
    val stressColor = Color(0xFFFF5252) // Red Neon
    val chartBackground = Color(0xFF1E1E1E) // Charcoal Dark

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // 1. HERO HEADER
        item {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Daily Rhythm",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                // INSIGHT GENERATOR
                val avgStress = if (stats.stressTrend.isNotEmpty()) stats.stressTrend.average() else 0.0
                val avgEnergy = if (stats.energyTrend.isNotEmpty()) stats.energyTrend.average() else 0.0

                val (insightText, insightColor) = when {
                    stats.totalMemories == 0 -> "Waiting for data..." to MaterialTheme.colorScheme.outline
                    avgStress > 0.6 -> "High Intensity • Prioritize recovery." to stressColor
                    avgEnergy > 0.6 -> "Peak Flow • You are in the zone." to energyColor
                    else -> "Balanced • Sustainable pace." to MaterialTheme.colorScheme.primary
                }

                Text(
                    text = insightText,
                    style = MaterialTheme.typography.titleMedium,
                    color = insightColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 2. THE LIVE CHART CARD
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = chartBackground),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Legend Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "24H TIMELINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                        Row {
                            LegendItem("ENERGY", energyColor)
                            Spacer(modifier = Modifier.width(16.dp))
                            LegendItem("STRESS", stressColor)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // THE CANVAS
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)) {

                        if (stats.totalMemories > 0) {
                            AnimatedChart(
                                stressData = stats.stressTrend,
                                energyData = stats.energyTrend,
                                stressColor = stressColor,
                                energyColor = energyColor,
                                progress = animationProgress.value
                            )
                        } else {
                            // Empty State
                            Text(
                                "Awaiting Data...",
                                modifier = Modifier.align(Alignment.Center),
                                color = Color.Gray.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Time Labels (X-Axis)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("00:00", "06:00", "12:00", "18:00", "23:59").forEach { time ->
                            Text(time, style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // 3. METRICS GRID
        item {
            Spacer(modifier = Modifier.height(24.dp))
            PaddingLabel("Highlights")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // FOCUS CARD
                val topActivity = stats.topActivities.firstOrNull()
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Top Focus",
                    value = topActivity?.first ?: "N/A",
                    subtext = "${topActivity?.second ?: 0} sessions",
                    icon = Icons.Default.Bolt,
                    accentColor = MaterialTheme.colorScheme.primary
                )

                // MOOD CARD
                val topEmotion = stats.topEmotions.firstOrNull()
                val stressLvl = if(stats.stressTrend.isNotEmpty()) stats.stressTrend.average() else 0.0

                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Mood",
                    value = topEmotion?.first ?: "Neutral",
                    subtext = "Avg Stress: ${(stressLvl * 10).toInt()}/10",
                    icon = Icons.Default.Psychology,
                    accentColor = if (stressLvl > 0.5) stressColor else energyColor
                )
            }
        }

        // 4. ACTIVITY DNA
        item {
            Spacer(modifier = Modifier.height(24.dp))
            PaddingLabel("Activity Breakdown")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (stats.topActivities.isEmpty()) {
                        Text("No activities recorded yet.", color = MaterialTheme.colorScheme.outline)
                    } else {
                        stats.topActivities.take(5).forEachIndexed { index, (label, count) ->
                            ActivityRow(label, count, stats.totalMemories, index)
                        }
                    }
                }
            }
        }
    }
}

// --- COMPONENTS ---

@Composable
fun ActivityRow(label: String, count: Int, total: Int, index: Int) {
    val progress = if (total > 0) count / total.toFloat() else 0f

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Label
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        // Bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .width(120.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surface,
        )

        // Percentage
        Text(
            text = "${(progress * 100).toInt()}%",
            modifier = Modifier.width(45.dp).padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtext: String,
    icon: ImageVector,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accentColor)
            }

            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtext, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PaddingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

// --- THE ANIMATED CHART ---
@Composable
fun AnimatedChart(
    stressData: List<Float>,
    energyData: List<Float>,
    stressColor: Color,
    energyColor: Color,
    progress: Float // 0f to 1f
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val stepX = width / 23f // 24 Hours

        // 1. Draw Subtle Grid
        val gridColor = Color.White.copy(alpha = 0.1f)
        drawLine(gridColor, Offset(0f, height), Offset(width, height))
        drawLine(gridColor, Offset(0f, height * 0.5f), Offset(width, height * 0.5f))
        drawLine(gridColor, Offset(0f, 0f), Offset(width, 0f))

        fun drawSmoothLine(data: List<Float>, color: Color) {
            if (data.isEmpty()) return

            val path = Path()
            val points = data.mapIndexed { index, value ->
                val safeVal = if (value.isNaN()) 0f else value
                Offset(index * stepX, height - (safeVal * height))
            }

            path.moveTo(points.first().x, points.first().y)
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val control = Offset((p0.x + p1.x) / 2, (p0.y + p1.y) / 2)
                path.quadraticBezierTo(p0.x, p0.y, control.x, control.y)
            }
            // Finish line
            if (points.size > 1) path.lineTo(points.last().x, points.last().y)

            // 2. CLIP RECTANGLE FOR ANIMATION
            // We clip the drawing based on 'progress'.
            // As progress goes 0->1, the clip rect expands width.
            clipRect(
                left = 0f,
                top = 0f,
                right = width * progress,
                bottom = height
            ) {
                // Gradient Fill
                val fillPath = Path()
                fillPath.addPath(path)
                fillPath.lineTo(points.last().x, height)
                fillPath.lineTo(points.first().x, height)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.3f), Color.Transparent),
                        startY = 0f,
                        endY = height
                    )
                )

                // The Stroke
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        drawSmoothLine(energyData, energyColor)
        drawSmoothLine(stressData, stressColor)
    }
}