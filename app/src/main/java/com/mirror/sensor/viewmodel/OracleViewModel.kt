package com.mirror.sensor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirror.sensor.data.model.Memory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// Data Models for the Stream
data class OracleMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val relatedMemoryId: String? = null // The "Evidence"
)

class OracleViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<OracleMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking = _isThinking.asStateFlow()

    // Initial "Proactive" State
    init {
        addSystemMessage("I'm active. I've analyzed 14 new memories today. What would you like to know?")
    }

    fun sendMessage(text: String, allMemories: List<Memory>) {
        if (text.isBlank()) return

        // 1. Add User Message
        val userMsg = OracleMessage(text = text, isUser = true)
        _messages.value = _messages.value + userMsg

        // 2. Simulate AI Processing
        viewModelScope.launch {
            _isThinking.value = true
            delay(1500) // Simulate network/processing latency

            // 3. Generate "Insight" (Mock Logic for Demo)
            val response = generateMockResponse(text, allMemories)
            _messages.value = _messages.value + response
            _isThinking.value = false
        }
    }

    private fun addSystemMessage(text: String) {
        _messages.value = _messages.value + OracleMessage(text = text, isUser = false)
    }

    // This would be replaced by your LLM/Backend call
    private fun generateMockResponse(query: String, memories: List<Memory>): OracleMessage {
        val lowerQuery = query.lowercase()

        // Dynamic mock responses based on keywords
        return when {
            lowerQuery.contains("stress") || lowerQuery.contains("anxious") -> {
                // Find a stressful memory to attach as "Evidence"
                val stressMemory = memories.find { it.psychological_profile.stress_level > 0.6 }
                OracleMessage(
                    text = "Your stress levels have been elevated in the afternoon. Specifically, the environment around 2:00 PM seems to be a trigger.",
                    isUser = false,
                    relatedMemoryId = stressMemory?.id
                )
            }
            lowerQuery.contains("happy") || lowerQuery.contains("good") -> {
                OracleMessage(
                    text = "You seemed most content during your morning routine. The audio analysis detected calm consistent tones.",
                    isUser = false,
                    relatedMemoryId = memories.firstOrNull()?.id
                )
            }
            else -> {
                OracleMessage(
                    text = "I'm analyzing that pattern. Based on your recent stream, this seems to be a recurring theme.",
                    isUser = false
                )
            }
        }
    }
}