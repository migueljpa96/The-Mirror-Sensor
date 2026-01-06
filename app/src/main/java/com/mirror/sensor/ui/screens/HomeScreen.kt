package com.mirror.sensor.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.ui.components.DateSelector
import com.mirror.sensor.ui.components.LiveMemoryCard
import com.mirror.sensor.ui.components.LiveTimelineItem
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
    onOpenControlCenter: () -> Unit,
    onMemoryClick: (String) -> Unit
) {
    val memories by viewModel.memories.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()

    // CHANGED: Observe date from ViewModel instead of local state
    val selectedDate by viewModel.selectedDate.collectAsState()

    val haptic = LocalHapticFeedback.current

    // Filter Logic
    val filteredMemories = remember(memories, selectedDate) {
        memories.filter { memory ->
            val memDate = memory.anchor_date?.toDate()
            memDate != null && isSameDay(memDate, selectedDate)
        }.sortedByDescending { it.anchor_date?.toDate() }
    }

    val isToday = isSameDay(selectedDate, Date())

    Column(modifier = Modifier.fillMaxSize()) {

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. HEADER
            item {
                Text(
                    "Your Stream",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 16.dp)
                )
            }

            // 2. DATE SELECTOR
            item {
                DateSelector(
                    selectedDate = selectedDate,
                    onDateSelected = { newDate ->
                        // CHANGED: Update ViewModel
                        viewModel.updateSelectedDate(newDate)
                    }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 3. LIVE SLOT (Always visible if Today)
            if (isToday) {
                item {
                    LiveTimelineItem(isRecording = isServiceRunning) {
                        LiveMemoryCard(
                            isRecording = isServiceRunning,
                            audioLevel = audioLevel,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenControlCenter()
                            }
                        )
                    }
                }
            }

            // 4. TIMELINE HISTORY
            if (filteredMemories.isEmpty()) {
                if (!isToday) {
                    item { EmptyStateView(selectedDate) }
                } else if (filteredMemories.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                            Text("No archives yet today.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
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

private fun isSameDay(d1: Date, d2: Date): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return fmt.format(d1) == fmt.format(d2)
}

@Composable
fun EmptyStateView(date: Date) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.ViewStream,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No archives found for this date.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}