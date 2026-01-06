package com.mirror.sensor.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.ui.components.MemoryCard
import com.mirror.sensor.viewmodel.HomeViewModel
import com.mirror.sensor.viewmodel.RecallViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecallScreen(
    onBack: () -> Unit,
    onMemoryClick: (String) -> Unit,
    homeViewModel: HomeViewModel = viewModel(),
    recallViewModel: RecallViewModel = viewModel()
) {
    // Connect Home Data to Recall Brain
    val allMemories by homeViewModel.memories.collectAsState()
    LaunchedEffect(allMemories) {
        recallViewModel.loadMemories(allMemories)
    }

    val query by recallViewModel.searchQuery.collectAsState()
    val active by recallViewModel.isActive.collectAsState()
    val results by recallViewModel.searchResults.collectAsState()
    val topics by recallViewModel.topics.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // SEARCH BAR (Takes over the top area)
            SearchBar(
                query = query,
                onQueryChange = { recallViewModel.onQueryChange(it) },
                onSearch = { recallViewModel.onActiveChange(false) },
                active = true, // Always "Expanded" style for this screen
                onActiveChange = { }, // Handled manually
                placeholder = { Text("Ask your memory...") },
                leadingIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { recallViewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // SEARCH CONTENT AREA
                if (query.isEmpty()) {
                    // 1. EMPTY STATE: TOPIC CLOUD
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TrendingUp, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Frequent Topics",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            topics.forEach { topic ->
                                SuggestionChip(
                                    onClick = { recallViewModel.onTopicClick(topic) },
                                    label = { Text(topic) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            }
                        }
                    }
                } else {
                    // 2. RESULTS LIST
                    if (results.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No matching memories found.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            item {
                                Text(
                                    "Found ${results.size} memories",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
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
    ) { innerPadding ->
        // Background content (hidden by SearchBar usually, but good for safety)
        Box(modifier = Modifier.padding(innerPadding))
    }
}