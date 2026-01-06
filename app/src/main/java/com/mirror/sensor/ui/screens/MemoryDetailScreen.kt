package com.mirror.sensor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp // <--- IMPORT THIS
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.data.model.Memory
import com.mirror.sensor.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDetailScreen(
    memoryId: String,
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val memories by viewModel.memories.collectAsState()
    val memory = memories.find { it.id == memoryId }

    if (memory == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val stress = memory.psychological_profile.stress_level
    val moodColor = when {
        stress > 0.8 -> Color(0xFFD32F2F)
        stress > 0.5 -> Color(0xFFF57C00)
        else -> Color(0xFF388E3C)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(memory.primary_activity.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(formatTime(memory), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 1. HEADER METRICS
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricBadge("Stress", "${(memory.psychological_profile.stress_level * 10).toInt()}/10", moodColor)
                MetricBadge("Energy", "${(memory.psychological_profile.energy_level * 100).toInt()}%", Color.Blue)
                MetricBadge("Load", "${(memory.psychological_profile.cognitive_load * 10).toInt()}/10", Color.Magenta)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. THE NARRATIVE
            SectionHeader("The Narrative", Icons.Default.AutoStories)
            Text(
                text = memory.narrative_summary,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp // <--- FIXED
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 3. THE EVIDENCE (Transcript + App)
            SectionHeader("The Reality (Evidence)", Icons.Default.Fingerprint)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Audio Evidence
                    if (memory.transcription.snippet.isNotEmpty() && memory.transcription.snippet != "Silence") {
                        EvidenceRow(Icons.Default.RecordVoiceOver, "Audio", "\"${memory.transcription.snippet}\"")
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Digital Evidence
                    EvidenceRow(Icons.Default.Smartphone, "Screen", memory.digital_context.active_app)

                    // Physical Evidence
                    Spacer(modifier = Modifier.height(12.dp))
                    EvidenceRow(Icons.Default.LocationOn, "Location", memory.environmental_context.inferred_location)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. THE BLACK BOX (Reasoning Trace)
            SectionHeader("The Mirror's Logic", Icons.Default.Psychology)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = memory._reasoning_trace,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )

                    if (memory.anomalies.detected_conflict != "None") {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "CONFLICT: ${memory.anomalies.detected_conflict}",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun MetricBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(color.copy(alpha = 0.2f))
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EvidenceRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

private fun formatTime(memory: Memory): String {
    val date = memory.anchor_date?.toDate() ?: java.util.Date()
    return SimpleDateFormat("EEEE, MMM d • HH:mm", Locale.getDefault()).format(date)
}