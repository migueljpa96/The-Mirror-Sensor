package com.mirror.sensor.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mirror.sensor.managers.RealTimeSensorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class SystemHealth(
    val micPermission: Boolean = false,
    val locationPermission: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationEnabled: Boolean = false
)

class ControlCenterViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val context = application.applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // 1. LIVE SENSOR DATA
    // Audio (0..1 normalized)
    val audioLevel = RealTimeSensorManager.audioLevel

    // Motion (G-Force deviation)
    private val _motionLevel = MutableStateFlow(0f)
    val motionLevel = _motionLevel.asStateFlow()

    // 2. SYSTEM HEALTH
    private val _health = MutableStateFlow(SystemHealth())
    val health = _health.asStateFlow()

    // 3. INTERNAL STATE
    private var gravity = FloatArray(3)
    private var linear_acceleration = FloatArray(3)

    init {
        startHealthCheck()
    }

    fun startSensors() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stopSensors() {
        sensorManager.unregisterListener(this)
    }

    private fun startHealthCheck() {
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

        _health.value = SystemHealth(hasMic, hasLoc, accessibilityEnabled, notificationEnabled)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val alpha = 0.8f

            // Isolate gravity
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

            // Remove gravity to get linear acceleration
            linear_acceleration[0] = event.values[0] - gravity[0]
            linear_acceleration[1] = event.values[1] - gravity[1]
            linear_acceleration[2] = event.values[2] - gravity[2]

            // Magnitude of movement
            val magnitude = sqrt(
                linear_acceleration[0] * linear_acceleration[0] +
                        linear_acceleration[1] * linear_acceleration[1] +
                        linear_acceleration[2] * linear_acceleration[2]
            )

            // Normalize for UI (0..1 approx)
            _motionLevel.value = (magnitude / 5f).coerceIn(0f, 1f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}