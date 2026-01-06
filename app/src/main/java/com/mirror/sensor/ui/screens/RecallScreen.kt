package com.mirror.sensor.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.ui.components.MemoryCard
import com.mirror.sensor.viewmodel.HomeViewModel
import com.mirror.sensor.viewmodel.RecallViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecallScreen(
    onBack: () -> Unit,
    onMemoryClick: (String) -> Unit,
    homeViewModel: HomeViewModel = viewModel(),
    recallViewModel: RecallViewModel = viewModel()
) {
    // Sync Data
    val allMemories by homeViewModel.memories.collectAsState()
    LaunchedEffect(allMemories) {
        recallViewModel.loadMemories(allMemories)
    }

    val query by recallViewModel.searchQuery.collectAsState()
    val results by recallViewModel.searchResults.collectAsState()
    val topics by recallViewModel.topics.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // 1. CUSTOM COMMAND BAR
            // Replaces the generic Material SearchBar with a cleaner input
            RecallSearchBar(
                query = query,
                onQueryChange = { recallViewModel.onQueryChange(it) },
                onBack = onBack,
                onClear = { recallViewModel.onQueryChange("") }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 2. CONTENT AREA
            if (query.isEmpty()) {
                // --- TOPIC CLOUD ---
                Text(
                    text = "DISCOVER CONTEXTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    topics.forEach { topic ->
                        TopicPill(
                            text = topic,
                            onClick = { recallViewModel.onTopicClick(topic) }
                        )
                    }
                }
            } else {
                // --- SEARCH RESULTS ---
                if (results.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching records.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item {
                            Text(
                                text = "FOUND ${results.size} MATCHES",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        items(results) { memory ->
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
}

// --- CUSTOM COMPONENTS ---

@Composable
fun RecallSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp)
    ) {
        // Back Button
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // Input Field
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "Recall a moment...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Action Icon (Clear or Search)
        if (query.isNotEmpty()) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(end = 12.dp)
            )
        }
    }
}

@Composable
fun TopicPill(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        color = Color.Transparent, // Wireframe look
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            letterSpacing = 0.5.sp
        )
    }
}