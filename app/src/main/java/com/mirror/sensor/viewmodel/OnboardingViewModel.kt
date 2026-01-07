package com.mirror.sensor.viewmodel

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OnboardingStep {
    TRANSPARENCY,
    SENSORS,
    DRILL
}

data class SensorState(
    val hasNotifications: Boolean = false,
    val hasMic: Boolean = false,
    val hasUsage: Boolean = false,
    val hasPhysical: Boolean = false,
    val hasLocation: Boolean = false // <--- NEW: Location State
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // PREFS: Track which permissions we have already requested
    private val prefs = context.getSharedPreferences("mirror_onboarding_prefs", Context.MODE_PRIVATE)

    private val _currentStep = MutableStateFlow(OnboardingStep.TRANSPARENCY)
    val currentStep = _currentStep.asStateFlow()

    private val _sensorState = MutableStateFlow(SensorState())
    val sensorState = _sensorState.asStateFlow()

    private val _drillActive = MutableStateFlow(false)
    val drillActive = _drillActive.asStateFlow()

    private val _drillComplete = MutableStateFlow(false)
    val drillComplete = _drillComplete.asStateFlow()

    init {
        checkSensors()
    }

    // --- NEW: PERMISSION TRACKING METHODS ---
    fun hasAskedPermission(permission: String): Boolean {
        return prefs.getBoolean("asked_$permission", false)
    }

    fun markPermissionRequested(permission: String) {
        prefs.edit().putBoolean("asked_$permission", true).apply()
    }

    // --- NAVIGATION ---
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

    // --- SENSOR CHECK ---
    fun checkSensors() {
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val hasPhysical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else true

        // NEW: Location Check
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        val hasUsage = mode == AppOpsManager.MODE_ALLOWED

        _sensorState.value = SensorState(hasNotif, hasMic, hasUsage, hasPhysical, hasLocation)
    }

    fun areAllSensorsGranted(): Boolean {
        val s = _sensorState.value
        // All 5 must be true
        return s.hasNotifications && s.hasMic && s.hasUsage && s.hasPhysical && s.hasLocation
    }

    fun moveToDrill() {
        if (areAllSensorsGranted()) {
            _currentStep.value = OnboardingStep.DRILL
        }
    }

    // --- DRILL LOGIC ---
    fun toggleDrill() {
        if (!_drillActive.value) {
            _drillActive.value = true
        } else {
            _drillActive.value = false
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        viewModelScope.launch {
            delay(500)
            _drillComplete.value = true
        }
    }

    // --- HELPER INTENTS ---
    fun openAppSettings(ctx: Context) {
        Toast.makeText(ctx, "Update permissions in Settings", Toast.LENGTH_SHORT).show()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", ctx.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun openUsageSettings(ctx: Context) {
        Toast.makeText(ctx, "Find 'The Mirror' and toggle ON", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}