package com.mirror.sensor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Date = Date(),
    val isThinking: Boolean = false
)

class OracleViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    init {
        // Initial Greeting
        _messages.value = listOf(
            ChatMessage(
                text = "I am The Mirror. I have been observing your day.\n\nAsk me anything about your timeline, stress levels, or activities.",
                isUser = false
            )
        )
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // 1. Add User Message
        val userMsg = ChatMessage(text = text, isUser = true)
        _messages.value = _messages.value + userMsg

        // 2. Simulate AI Thinking
        viewModelScope.launch {
            _isTyping.value = true
            delay(1500) // Fake network delay

            // 3. Mock Response (Placeholder for Cloud Function)
            // Later, we will replace this with: val response = firebaseFunctions.call("askTheMirror", text)
            val aiResponseText = generateMockResponse(text)

            _isTyping.value = false
            _messages.value = _messages.value + ChatMessage(text = aiResponseText, isUser = false)
        }
    }

    private fun generateMockResponse(query: String): String {
        return when {
            query.contains("stress", true) ->
                "I noticed your stress levels peaked around 2:00 PM while you were using Slack. It seems work communications are a trigger today."
            query.contains("focus", true) ->
                "You have been in 'Deep Work' mode for 3 hours today. This is 20% higher than your average."
            else ->
                "I have recorded that in your timeline. Is there a specific pattern you would like me to analyze?"
        }
    }
}