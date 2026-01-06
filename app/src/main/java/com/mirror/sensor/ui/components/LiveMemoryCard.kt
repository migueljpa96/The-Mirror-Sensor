package com.mirror.sensor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Composable
fun LiveMemoryCard(
    isRecording: Boolean,
    audioLevel: Float,
    onClick: () -> Unit
) {
    // Colors & Animation based on state
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline

    val stripColor by animateColorAsState(
        targetValue = if (isRecording) primaryColor else outlineColor.copy(alpha = 0.5f),
        label = "color"
    )

    // Pulse Animation (Only when recording)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "alpha"
    )
    val activeAlpha = if (isRecording) alpha else 1f

    // Timer Logic
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (true) {
                seconds = (System.currentTimeMillis() - startTime) / 1000
                delay(1000)
            }
        } else {
            seconds = 0
        }
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {

            // 1. The Strip (Pulsing or Static)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(stripColor.copy(alpha = activeAlpha))
            )

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                // 2. Header
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRecording) {
                            Icon(
                                imageVector = Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(10.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PauseCircle,
                                contentDescription = null,
                                tint = outlineColor,
                                modifier = Modifier.size(12.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = if (isRecording) "CAPTURING REALITY..." else "SYSTEM STANDBY",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isRecording) primaryColor else outlineColor,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Duration Timer (Hidden if standby)
                    if (isRecording) {
                        Text(
                            text = formatDuration(seconds),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = primaryColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Waveform (Live or Flatline)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    LiveWaveform(
                        amplitude = if (isRecording) audioLevel else 0f,
                        color = if (isRecording) primaryColor else outlineColor
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isRecording) "Tap to inspect sensors" else "Tap to configure sensors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun LiveWaveform(amplitude: Float, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val barCount = 40
        val barWidth = size.width / barCount
        val centerY = size.height / 2

        // Draw flatline if 0
        if (amplitude == 0f) {
            drawLine(
                color = color.copy(alpha = 0.3f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 2.dp.toPx()
            )
            return@Canvas
        }

        for (i in 0 until barCount) {
            val randomScale = Random.nextFloat() * 0.8f + 0.2f
            val height = (amplitude * size.height * randomScale).coerceAtLeast(2f)
            val x = i * barWidth + (barWidth / 2)

            drawLine(
                color = color.copy(alpha = 0.6f),
                start = Offset(x, centerY - height / 2),
                end = Offset(x, centerY + height / 2),
                strokeWidth = barWidth * 0.6f,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = TimeUnit.SECONDS.toMinutes(seconds)
    val s = seconds - TimeUnit.MINUTES.toSeconds(m)
    return String.format("%02d:%02d", m, s)
}