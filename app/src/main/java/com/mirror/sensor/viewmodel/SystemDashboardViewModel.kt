package com.mirror.sensor.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mirror.sensor.managers.DashboardSensorManager
import com.mirror.sensor.managers.RealTimeSensorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.log10

data class SystemStatus(
    val micPermission: Boolean = false,
    val locationPermission: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationEnabled: Boolean = false
)

data class TerminalLog(
    val timestamp: String,
    val message: String
)

class SystemDashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // Sensors
    private val sensorManager = DashboardSensorManager(context)
    val inertialData = sensorManager.inertialData

    // Audio dB Calculation (Same logic, cleaner implementation)
    val audioDecibels = RealTimeSensorManager.audioLevel.map { amp ->
        if (amp <= 0.001f) -60f else 20f * log10(amp)
    }.stateIn(viewModelScope, SharingStarted.Lazily, -60f)

    // Status
    private val _status = MutableStateFlow(SystemStatus())
    val status = _status.asStateFlow()

    // Logs
    private val _logs = MutableStateFlow<List<TerminalLog>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        startDiagnosticLoop()
    }

    fun startSensors() = sensorManager.startListening()
    fun stopSensors() = sensorManager.stopListening()

    private fun startDiagnosticLoop() {
        viewModelScope.launch {
            while (true) {
                checkPermissions()
                delay(2000)
            }
        }
    }

    private fun checkPermissions() {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val accessibilityEnabled = try {
            val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            enabledServices?.contains(context.packageName) == true
        } catch (e: Exception) { false }

        val notificationEnabled = try {
            val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            enabledListeners?.contains(context.packageName) == true
        } catch (e: Exception) { false }

        val newStatus = SystemStatus(hasMic, hasLoc, accessibilityEnabled, notificationEnabled)

        if (newStatus != _status.value) {
            _status.value = newStatus
            if (!hasMic) addLog("Microphone access is missing")
            if (!accessibilityEnabled) addLog("Accessibility service disconnected")
        }
    }

    private fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = TerminalLog(time, msg)
        _logs.value = (_logs.value + newLog).takeLast(10)
    }
}