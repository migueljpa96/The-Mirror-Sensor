package com.mirror.sensor.data.model

import com.google.firebase.Timestamp

data class Memory(
    val id: String = "", // Document ID (e.g. "20260104_180000")
    val timestamp_id: String = "",
    val anchor_date: Timestamp? = null, // Firestore uses Timestamp, not Date directly
    val narrative_summary: String = "Processing reality...",
    val key_insights: List<String> = emptyList(),

    // Nested Objects
    val primary_activity: ActivityData = ActivityData(),
    val psychological_profile: PsychData = PsychData(),
    val environmental_context: EnvData = EnvData()
)

data class ActivityData(
    val label: String = "Unknown",
    val category: String = "Idle",
    val status: String = "",
    val confidence: Double = 0.0
)

data class PsychData(
    val dominant_emotion: String = "Neutral",
    val energy_level: Double = 0.5,
    val stress_level: Double = 0.5,
    val cognitive_load: Double = 0.5,
    val emotional_trend: String = "Stable"
)

data class EnvData(
    val inferred_location: String = "Unknown",
    val social_context: String = "Alone",
    val auditory_texture: String = ""
)