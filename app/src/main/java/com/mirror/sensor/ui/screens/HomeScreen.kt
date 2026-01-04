package com.mirror.sensor.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.ui.components.MemoryCard
import com.mirror.sensor.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onStartService: () -> Unit = {} // Keep this for the FAB
) {
    val memories by viewModel.memories.collectAsState()

    // REMOVED: TopBar (Moved to parent if needed, or keep local)
    // We KEEP the FAB here because it's specific to the Stream action

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onStartService,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Text("Start Mirror")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding) // Consumes the FAB padding
                .fillMaxSize()
        ) {
            // Header Title (Since we removed TopBar)
            Text(
                text = "The Mirror",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            if (memories.isEmpty()) {
                Text(
                    text = "Waiting for reality...",
                    modifier = Modifier.padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(memories) { memory ->
                        MemoryCard(memory = memory)
                    }
                }
            }
        }
    }
}