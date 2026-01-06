package com.mirror.sensor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirror.sensor.data.model.Memory
import java.text.SimpleDateFormat
import java.util.Locale

// --- SHARED COLOR LOGIC ---
fun getStressColor(stressLevel: Double): Color {
    return when {
        stressLevel > 0.8 -> Color(0xFFE53935) // Red 600
        stressLevel > 0.5 -> Color(0xFFFB8C00) // Orange 600
        else -> Color(0xFF43A047)              // Green 600
    }
}

@Composable
fun MemoryCard(memory: Memory, onClick: () -> Unit) {
    val moodColor = getStressColor(memory.psychological_profile.stress_level)

    // Flat Surface instead of elevated card for cleaner list look
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, // Subtle separation
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {

            // 1. The Mood Strip (Slimmer, cleaner)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(moodColor)
            )

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                // 2. Header: Title (Activity) & Time
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = memory.primary_activity.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    val date = memory.anchor_date?.toDate() ?: java.util.Date()
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Narrative Body
                Text(
                    text = memory.narrative_summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Footer Pills
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp) // Tighter spacing
                ) {
                    // Energy Pill
                    val energyLevel = memory.psychological_profile.energy_level
                    val energyText = when {
                        energyLevel > 0.7 -> "High"
                        energyLevel > 0.3 -> "Med"
                        else -> "Low"
                    }
                    DataPill(Icons.Default.Bolt, energyText)

                    // Location Pill
                    DataPill(Icons.Default.LocationOn, memory.environmental_context.inferred_location)

                    // Emotion Pill
                    DataPill(Icons.Default.Psychology, memory.psychological_profile.dominant_emotion)
                }
            }
        }
    }
}

@Composable
fun DataPill(icon: ImageVector, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 80.dp)
            )
        }
    }
}