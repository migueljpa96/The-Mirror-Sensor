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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.ui.components.DateSelector
import com.mirror.sensor.ui.components.MemoryCard
import com.mirror.sensor.ui.components.TimelineItem
import com.mirror.sensor.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    isServiceRunning: Boolean = false, // Kept for API compatibility, but unused visually
    onMemoryClick: (String) -> Unit
) {
    val memories by viewModel.memories.collectAsState()

    var selectedDate by remember { mutableStateOf(Date()) }

    val filteredMemories = remember(memories, selectedDate) {
        memories.filter { memory ->
            val memDate = memory.anchor_date?.toDate()
            memDate != null && isSameDay(memDate, selectedDate)
        }.sortedByDescending { it.anchor_date?.toDate() }
    }

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
                    onDateSelected = { selectedDate = it }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 3. TIMELINE
            if (filteredMemories.isEmpty()) {
                item { EmptyStateView(selectedDate) }
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

// Helpers
private fun isSameDay(d1: Date, d2: Date): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return fmt.format(d1) == fmt.format(d2)
}

@Composable
fun EmptyStateView(date: Date) {
    val isToday = isSameDay(date, Date())
    val message = if (isToday) "No memories yet today." else "No memories found for this day."

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
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}