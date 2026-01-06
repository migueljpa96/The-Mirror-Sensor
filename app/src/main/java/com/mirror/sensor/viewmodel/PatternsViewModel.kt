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

class PatternsViewModel : ViewModel() {

    private val _stats = MutableStateFlow(DailyStats())
    val stats: StateFlow<DailyStats> = _stats.asStateFlow()

    fun calculateStats(memories: List<Memory>) {
        viewModelScope.launch {
            if (memories.isEmpty()) return@launch

            // 1. Sort by time
            val sorted = memories.sortedBy { it.anchor_date?.toDate() ?: Date() }

            // 2. Extract Hourly Data points (0..23)
            val stressPoints = MutableList(24) { 0f }
            val energyPoints = MutableList(24) { 0f }
            val counts = MutableList(24) { 0 }

            sorted.forEach { memory ->
                val date = memory.anchor_date?.toDate() ?: return@forEach
                val cal = Calendar.getInstance()
                cal.time = date
                val hour = cal.get(Calendar.HOUR_OF_DAY)

                stressPoints[hour] += memory.psychological_profile.stress_level.toFloat()
                energyPoints[hour] += memory.psychological_profile.energy_level.toFloat()
                counts[hour]++
            }

            // Average the points
            val finalStress = stressPoints.mapIndexed { index, total ->
                if (counts[index] > 0) total / counts[index] else 0f
            }
            val finalEnergy = energyPoints.mapIndexed { index, total ->
                if (counts[index] > 0) total / counts[index] else 0f
            }

            // 3. Activity Distribution
            val activities = sorted.groupingBy { it.primary_activity.label }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .take(5) // Top 5

            // 4. Dominant Emotions
            val emotions = sorted.groupingBy { it.psychological_profile.dominant_emotion }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .take(4)

            _stats.value = DailyStats(
                stressTrend = finalStress,
                energyTrend = finalEnergy,
                topActivities = activities,
                topEmotions = emotions,
                totalMemories = sorted.size
            )
        }
    }
}

data class DailyStats(
    val stressTrend: List<Float> = emptyList(),
    val energyTrend: List<Float> = emptyList(),
    val topActivities: List<Pair<String, Int>> = emptyList(),
    val topEmotions: List<Pair<String, Int>> = emptyList(),
    val totalMemories: Int = 0
)