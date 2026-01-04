package com.mirror.sensor.data.model

import java.util.Date

data class DailySummary(
    val date: String = "",
    val narrative_arc: String = "No summary available yet.",
    val energy_analysis: String = "",
    val dominant_mood: String = "Neutral",
    val productivity_score: Double = 0.0,
    val key_patterns: List<String> = emptyList(),
    val memory_count: Int = 0,
    val generated_at: Date = Date()
)