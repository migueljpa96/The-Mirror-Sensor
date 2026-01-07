package com.mirror.sensor.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.mirror.sensor.services.MasterService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)

    // Service State
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning = _isServiceRunning.asStateFlow()

    // Onboarding State
    private val _hasCompletedOnboarding = MutableStateFlow(prefs.getBoolean("onboarding_complete", false))
    val hasCompletedOnboarding = _hasCompletedOnboarding.asStateFlow()

    fun setOnboardingComplete() {
        prefs.edit { putBoolean("onboarding_complete", true) }
        _hasCompletedOnboarding.value = true
    }

    // --- NEW METHODS FOR MAINACTIVITY ---

    fun startService() {
        MasterService.startService(context)
        _isServiceRunning.value = true
    }

    fun stopService() {
        MasterService.stopService(context)
        _isServiceRunning.value = false
    }
}