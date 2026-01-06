package com.mirror.sensor.managers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

data class InertialData(
    val gForce: Float = 1f,
    val isStationary: Boolean = true
)

class DashboardSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _inertialData = MutableStateFlow(InertialData())
    val inertialData: StateFlow<InertialData> = _inertialData.asStateFlow()

    private var gravity = FloatArray(3)
    private val ALPHA = 0.2f

    fun startListening() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Low-pass filter
            gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * event.values[0]
            gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * event.values[1]
            gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * event.values[2]

            val g = sqrt(gravity[0]*gravity[0] + gravity[1]*gravity[1] + gravity[2]*gravity[2]) / 9.81f
            val stationary = g in 0.95..1.05

            _inertialData.value = InertialData(g, stationary)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}