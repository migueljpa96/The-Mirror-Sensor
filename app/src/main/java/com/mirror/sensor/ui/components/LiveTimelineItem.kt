package com.mirror.sensor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LiveTimelineItem(
    isRecording: Boolean,
    content: @Composable () -> Unit
) {
    // Pulse only if recording
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by if (isRecording) {
        infiniteTransition.animateFloat(
            initialValue = 0.8f, targetValue = 1.2f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "scale"
        )
    } else {
        remember { mutableFloatStateOf(1f) } // Static if idle
    }

    val dotColor by animateColorAsState(
        targetValue = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "color"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // --- TIMELINE TRACK ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight()
        ) {
            Spacer(modifier = Modifier.height(24.dp)) // Offset

            // The Node
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            // Bottom Line (Connects to history)
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        // --- CONTENT ---
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 16.dp, end = 16.dp)
        ) {
            content()
        }
    }
}