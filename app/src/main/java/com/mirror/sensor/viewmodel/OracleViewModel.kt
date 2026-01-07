package com.mirror.sensor.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.functions.functions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class OracleViewModel(application: Application) : AndroidViewModel(application) {

    // State
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Firebase
    private val functions = Firebase.functions("us-central1") // Ensure region matches Cloud Code

    init {
        // Initial Greeting
        addBotMessage("I am the Oracle. I have been watching. What do you wish to know about your day?")
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // 1. Add User Message immediately
        val userMsg = ChatMessage(text = text, isUser = true)
        _messages.value = _messages.value + userMsg

        _isLoading.value = true

        // 2. Call Cloud Function
        viewModelScope.launch {
            try {
                val response = callOracleFunction(text)
                addBotMessage(response)
            } catch (e: Exception) {
                Log.e("OracleViewModel", "Error calling oracle", e)
                addBotMessage("The connection to the Ether is weak. Please try again. (${e.message})")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun callOracleFunction(query: String): String {
        // The data to send
        val data = hashMapOf(
            "text" to query
        )

        return try {
            val result = functions
                .getHttpsCallable("askOracle")
                .call(data)
                .await()

            val resultData = result.data as? Map<*, *>
            resultData?.get("answer") as? String ?: "I heard you, but formed no thought."
        } catch (e: Exception) {
            throw e
        }
    }

    private fun addBotMessage(text: String) {
        val botMsg = ChatMessage(text = text, isUser = false)
        _messages.value = _messages.value + botMsg
    }
}