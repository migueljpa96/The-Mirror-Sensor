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
    memory: Memory, // Need memory to calculate color
    isLast: Boolean,
    content: @Composable () -> Unit
) {
    // SYNC COLOR: Use the exact same logic as MemoryCard
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
                .width(56.dp)
                .fillMaxHeight()
        ) {
            // Top Line
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            // The Node (Dot) -> COLORED BY STRESS
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor) // <--- SYNCED COLOR
            )

            // Bottom Line
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(if (isLast) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        // --- CONTENT ---
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 24.dp, end = 16.dp)
        ) {
            content()
        }
    }
}