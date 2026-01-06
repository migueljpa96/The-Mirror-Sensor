package com.mirror.sensor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mirror.sensor.data.model.Memory

@Composable
fun TimelineItem(
    memory: Memory,
    isLast: Boolean,
    content: @Composable () -> Unit
) {
    val dotColor = getStressColor(memory.psychological_profile.stress_level)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // --- TIMELINE TRACK ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(48.dp) // Slightly slimmer track width
                .fillMaxHeight()
        ) {
            // Top Line
            Box(
                modifier = Modifier
                    .width(1.dp) // Thinner elegant line
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            // The Node (Dot)
            Box(
                modifier = Modifier
                    .size(10.dp) // Smaller, sharper dot
                    .clip(CircleShape)
                    .background(dotColor)
            )

            // Bottom Line
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .weight(1f)
                    .background(if (isLast) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
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