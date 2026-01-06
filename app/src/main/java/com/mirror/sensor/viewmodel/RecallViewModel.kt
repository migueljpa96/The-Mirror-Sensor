package com.mirror.sensor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirror.sensor.data.model.Memory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecallViewModel : ViewModel() {

    // 1. INPUT STATE
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isActive = MutableStateFlow(false) // Search Bar Open/Close
    val isActive = _isActive.asStateFlow()

    // 2. DATA STATE
    private val _allMemories = MutableStateFlow<List<Memory>>(emptyList())
    private val _searchResults = MutableStateFlow<List<Memory>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    // 3. SMART TOPICS (Auto-generated from data)
    private val _topics = MutableStateFlow<List<String>>(emptyList())
    val topics = _topics.asStateFlow()

    // Initialize with data (In a real app, this would come from a Repository)
    fun loadMemories(memories: List<Memory>) {
        _allMemories.value = memories
        generateTopics(memories)
    }

    private fun generateTopics(memories: List<Memory>) {
        // Extract top 8 most frequent Activity Labels & Emotions
        val activityCounts = memories.groupingBy { it.primary_activity.label }.eachCount()
        val emotionCounts = memories.groupingBy { it.psychological_profile.dominant_emotion }.eachCount()

        val topActivities = activityCounts.entries.sortedByDescending { it.value }.take(4).map { it.key }
        val topEmotions = emotionCounts.entries.sortedByDescending { it.value }.take(4).map { it.key }

        _topics.value = (topActivities + topEmotions).distinct().filter { it.isNotEmpty() && it != "Unknown" }
    }

    fun onQueryChange(query: String) {
        _searchQuery.value = query
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
        } else {
            performSearch(query)
        }
    }

    fun onActiveChange(active: Boolean) {
        _isActive.value = active
        if (!active) {
            _searchQuery.value = ""
            _searchResults.value = emptyList()
        }
    }

    private fun performSearch(query: String) {
        val lowerQuery = query.lowercase()
        _searchResults.value = _allMemories.value.filter { memory ->
            // Search everywhere: Narrative, Transcript, Tags, Location
            memory.narrative_summary.lowercase().contains(lowerQuery) ||
                    memory.primary_activity.label.lowercase().contains(lowerQuery) ||
                    memory.environmental_context.inferred_location.lowercase().contains(lowerQuery) ||
                    memory.psychological_profile.dominant_emotion.lowercase().contains(lowerQuery)
        }
    }

    fun onTopicClick(topic: String) {
        onQueryChange(topic)
        _isActive.value = true
    }
}