package com.mirror.sensor.data.model

import com.google.firebase.Timestamp

data class Memory(
    val id: String = "",
    val timestamp_id: String = "",
    val anchor_date: Timestamp? = null,
    val narrative_summary: String = "Processing reality...",
    val key_insights: List<String> = emptyList(),

    // --- NEW: THE DEEP DIVE DATA ---
    val _reasoning_trace: String = "", // The AI's internal monologue

    // Nested Objects
    val primary_activity: ActivityData = ActivityData(),
    val psychological_profile: PsychData = PsychData(),
    val environmental_context: EnvData = EnvData(),

    // New Structures
    val transcription: TranscriptData = TranscriptData(),
    val digital_context: DigitalData = DigitalData(),
    val anomalies: AnomalyData = AnomalyData()
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

// --- NEW CLASSES ---
data class TranscriptData(
    val snippet: String = "",
    val speaker: String = "None"
)

data class DigitalData(
    val active_app: String = "None",
    val inferred_intent: String = "Offline"
)

data class AnomalyData(
    val detected_conflict: String = "None",
    val habit_alert: String = "None"
)