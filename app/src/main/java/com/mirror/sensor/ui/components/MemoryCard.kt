package com.mirror.sensor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirror.sensor.data.model.Memory
import java.text.SimpleDateFormat
import java.util.Locale

// --- SHARED COLOR LOGIC ---
// Public function so TimelineItem can use the exact same color
fun getStressColor(stressLevel: Double): Color {
    return when {
        stressLevel > 0.8 -> Color(0xFFD32F2F) // High Stress (Red)
        stressLevel > 0.5 -> Color(0xFFF57C00) // Moderate (Orange)
        else -> Color(0xFF388E3C)              // Flow/Calm (Green)
    }
}

@Composable
fun MemoryCard(memory: Memory, onClick: () -> Unit) {
    // 1. Get Color from shared logic
    val moodColor = getStressColor(memory.psychological_profile.stress_level)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(),
        // Remove horizontal padding here since TimelineItem handles spacing
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // The "Mood Strip"
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(moodColor)
            )

            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
            ) {
                // Header
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = memory.primary_activity.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    // Format Timestamp
                    val date = memory.anchor_date?.toDate() ?: java.util.Date()
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // The Story
                Text(
                    text = memory.narrative_summary,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 18.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Data Context Row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Energy: Converted to Text
                    val energyLevel = memory.psychological_profile.energy_level
                    val energyText = when {
                        energyLevel > 0.7 -> "High"
                        energyLevel > 0.3 -> "Medium"
                        else -> "Low"
                    }
                    InfoChip(Icons.Default.Bolt, energyText)

                    Spacer(modifier = Modifier.width(12.dp))

                    // Location
                    InfoChip(Icons.Default.LocationOn, memory.environmental_context.inferred_location)

                    Spacer(modifier = Modifier.width(12.dp))

                    // Emotion
                    InfoChip(Icons.Default.Psychology, memory.psychological_profile.dominant_emotion)
                }
            }
        }
    }
}

@Composable
fun InfoChip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}