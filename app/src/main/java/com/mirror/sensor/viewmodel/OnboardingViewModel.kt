package com.mirror.sensor.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OnboardingStep { TRANSPARENCY, SENSORS, DRILL }

data class SensorState(
    val hasNotifications: Boolean = false,
    val hasMic: Boolean = false,
    val hasLocation: Boolean = false
    // Removed hasUsage
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("mirror_onboarding_prefs", Context.MODE_PRIVATE)

    private val _currentStep = MutableStateFlow(OnboardingStep.TRANSPARENCY)
    val currentStep = _currentStep.asStateFlow()
    private val _sensorState = MutableStateFlow(SensorState())
    val sensorState = _sensorState.asStateFlow()
    private val _drillActive = MutableStateFlow(false)
    val drillActive = _drillActive.asStateFlow()
    private val _drillComplete = MutableStateFlow(false)
    val drillComplete = _drillComplete.asStateFlow()

    init { checkSensors() }

    fun hasAskedPermission(permission: String) = prefs.getBoolean("asked_$permission", false)
    fun markPermissionRequested(permission: String) = prefs.edit().putBoolean("asked_$permission", true).apply()

    fun navigateBack() {
        when (_currentStep.value) {
            OnboardingStep.SENSORS -> _currentStep.value = OnboardingStep.TRANSPARENCY
            OnboardingStep.DRILL -> _currentStep.value = OnboardingStep.SENSORS
            else -> {}
        }
    }

    fun completeTransparency() {
        _currentStep.value = OnboardingStep.SENSORS
        checkSensors()
    }

    fun checkSensors() {
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // Usage Stats logic removed

        _sensorState.value = SensorState(hasNotif, hasMic, hasLocation)
    }

    fun areAllSensorsGranted(): Boolean {
        val s = _sensorState.value
        return s.hasNotifications && s.hasMic && s.hasLocation
    }

    fun moveToDrill() { if (areAllSensorsGranted()) _currentStep.value = OnboardingStep.DRILL }

    fun toggleDrill() {
        if (!_drillActive.value) _drillActive.value = true
        else {
            _drillActive.value = false
            viewModelScope.launch { delay(500); _drillComplete.value = true }
        }
    }

    fun openAppSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", ctx.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}