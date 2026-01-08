package com.mirror.sensor.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mirror.sensor.data.db.AppDatabase
import com.mirror.sensor.managers.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionManager = SessionManager(application)
    private val dao = AppDatabase.getDatabase(application).uploadDao()
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)

    // Service State (Matches MainScreen expectation)
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    // Onboarding State (Restored)
    private val _hasCompletedOnboarding = MutableStateFlow(prefs.getBoolean("onboarding_complete", false))
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    init {
        checkTrackingState()
    }

    private fun checkTrackingState() {
        viewModelScope.launch {
            // Check DB for truth
            val activeSession = dao.getActiveSession()
            _isServiceRunning.value = activeSession != null && activeSession.is_active
        }
    }

    fun setOnboardingComplete() {
        prefs.edit { putBoolean("onboarding_complete", true) }
        _hasCompletedOnboarding.value = true
    }

    fun startService() {
        viewModelScope.launch {
            // TODO: Pass actual user ID in future
            sessionManager.startSession("user_local")
            _isServiceRunning.value = true
        }
    }

    fun stopService() {
        viewModelScope.launch {
            sessionManager.stopSession()
            _isServiceRunning.value = false
        }
    }
}