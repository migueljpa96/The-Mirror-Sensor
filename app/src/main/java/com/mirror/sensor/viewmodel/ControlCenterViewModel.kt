package com.mirror.sensor.viewmodel

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mirror.sensor.managers.RealTimeSensorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class SystemHealth(
    val notificationPermission: Boolean = false, // Transparency
    val micPermission: Boolean = false,          // Session Audio
    val usageStatsPermission: Boolean = false,   // Focus Tracking
    val physicalPermission: Boolean = false,     // Biometric Sync
    val locationPermission: Boolean = false      // Spatial Context
)

class ControlCenterViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val context = application.applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // 1. LIVE SENSOR DATA
    val audioLevel = RealTimeSensorManager.audioLevel
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
        // 1. Transparency (Notifications)
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        // 2. Audio
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // 3. Physical
        val hasPhysical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else true

        // 4. Location
        val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // 5. Focus (Usage Stats)
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        val hasUsage = mode == AppOpsManager.MODE_ALLOWED

        _health.value = SystemHealth(hasNotif, hasMic, hasUsage, hasPhysical, hasLoc)
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

            // Magnitude
            val magnitude = sqrt(
                linear_acceleration[0] * linear_acceleration[0] +
                        linear_acceleration[1] * linear_acceleration[1] +
                        linear_acceleration[2] * linear_acceleration[2]
            )

            _motionLevel.value = (magnitude / 5f).coerceIn(0f, 1f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}