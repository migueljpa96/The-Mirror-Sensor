package com.mirror.sensor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirror.sensor.data.model.Memory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

data class ReflectionState(
    val narrative: String = "Analyzing your day...",
    val avgEnergy: Float = 0f,
    val avgStress: Float = 0f,
    val timeDistribution: List<TimeSegment> = emptyList(),
    val topTopics: List<String> = emptyList()
)

data class TimeSegment(
    val label: String,
    val hours: Float,
    val colorHex: Long
)

class ReflectionViewModel : ViewModel() {

    private val _state = MutableStateFlow(ReflectionState())
    val state = _state.asStateFlow()

    fun analyze(memories: List<Memory>) {
        viewModelScope.launch {
            if (memories.isEmpty()) {
                _state.value = ReflectionState(narrative = "No data recorded today.")
                return@launch
            }

            // Filter for Today
            val today = Calendar.getInstance()
            val todayMemories = memories.filter {
                val memDate = it.anchor_date?.toDate() ?: Date()
                val memCal = Calendar.getInstance().apply { time = memDate }
                memCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            }

            if (todayMemories.isEmpty()) {
                _state.value = ReflectionState(narrative = "No memories captured yet today.")
                return@launch
            }

            // 1. CALCULATE VITALS
            val avgEnergy = todayMemories.map { it.psychological_profile.energy_level }.average().toFloat()
            val avgStress = todayMemories.map { it.psychological_profile.stress_level }.average().toFloat()

            // 2. CALCULATE CONTEXT (Time Distribution)
            // Simplified: Grouping by Location for now
            val locationCounts = todayMemories.groupingBy { it.environmental_context.inferred_location }
                .eachCount()

            val total = todayMemories.size.toFloat()
            val segments = locationCounts.map { (loc, count) ->
                // Assign simplified colors based on location type
                val color = when {
                    loc.contains("Home", true) -> 0xFF4CAF50 // Green
                    loc.contains("Work", true) || loc.contains("Office", true) -> 0xFF2196F3 // Blue
                    loc.contains("Transit", true) || loc.contains("Car", true) -> 0xFFFFC107 // Amber
                    else -> 0xFF9E9E9E // Gray
                }
                // Estimate time: Assuming each memory represents ~15-30 mins or sparse sampling
                // For visualization, we just use relative percentage
                TimeSegment(loc, (count / total) * 100f, color)
            }.sortedByDescending { it.hours }

            // 3. TOPICS (Mind)
            // Extract from labels + emotion
            val allTags = todayMemories.flatMap {
                listOf(it.primary_activity.label, it.psychological_profile.dominant_emotion)
            }
            val topics = allTags.groupingBy { it }
                .eachCount()
                .entries.sortedByDescending { it.value }
                .take(6)
                .map { it.key }
                .filter { it.isNotEmpty() && it != "Unknown" }

            // 4. GENERATE NARRATIVE (Heuristic)
            val narrative = buildNarrative(avgEnergy, avgStress, segments.firstOrNull()?.label ?: "Home")

            _state.value = ReflectionState(
                narrative = narrative,
                avgEnergy = avgEnergy,
                avgStress = avgStress,
                timeDistribution = segments,
                topTopics = topics
            )
        }
    }

    private fun buildNarrative(energy: Float, stress: Float, topLoc: String): String {
        val energyText = when {
            energy > 0.7 -> "high-energy"
            energy < 0.4 -> "low-energy"
            else -> "balanced"
        }

        val stressText = when {
            stress > 0.7 -> "stress levels were elevated"
            stress < 0.3 -> "you remained calm"
            else -> "stress was moderate"
        }

        return "You've had a $energyText day mostly spent at $topLoc. Overall, $stressText throughout the day."
    }
}