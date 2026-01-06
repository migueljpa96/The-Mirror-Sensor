package com.mirror.sensor.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

object RealTimeSensorManager {
    // 0.0 to 1.0 Normalized Audio Level
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    fun updateAmplitude(rawAmplitude: Int) {
        // Normalize: Max amplitude is usually ~32767 for 16-bit audio.
        // We clamp it to 0..1 range for easier UI rendering.
        val normalized = min(rawAmplitude / 20000f, 1f)
        _audioLevel.value = normalized
    }
}