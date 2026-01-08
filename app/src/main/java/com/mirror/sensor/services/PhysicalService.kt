package com.mirror.sensor.services

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

class PhysicalService : Service(), SensorEventListener {

    private val binder = LocalBinder()
    private var sensorManager: SensorManager? = null

    // writingScope handles disk IO off the main thread
    private val writingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<String>(capacity = 1000) // Buffer sensor events

    private var currentWriter: BufferedWriter? = null
    private var isLogging = false

    inner class LocalBinder : Binder() {
        fun getService(): PhysicalService = this@PhysicalService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        registerSensors()

        // Start the consumer loop
        writingScope.launch {
            for (line in logChannel) {
                try {
                    currentWriter?.write(line)
                    currentWriter?.newLine()
                } catch (e: IOException) {
                    Log.e(TAG, "Write failed", e)
                }
            }
        }
    }

    private fun registerSensors() {
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // SENSOR_DELAY_NORMAL (200ms) is sufficient for "Lifecycle" logging without killing battery
        // For higher fidelity, use SENSOR_DELAY_GAME
        sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isLogging || event == null) return

        // Format: {"ts":12345,"type":"A","v":[x,y,z]}
        // Minimal string concatenation for performance
        val typeChar = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) "A" else "G"
        val line = "{\"ts\":${System.currentTimeMillis()},\"t\":\"$typeChar\",\"v\":[${event.values[0]},${event.values[1]},${event.values[2]}]}"

        // Non-blocking send
        logChannel.trySend(line)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    // --- Control Methods called by SessionManager ---

    fun startLogging(file: File) {
        Log.i(TAG, "Starting logging to: ${file.name}")
        closeCurrentFile()
        try {
            currentWriter = BufferedWriter(FileWriter(file, true))
            isLogging = true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open file", e)
        }
    }

    fun rotateLog(newFile: File) {
        Log.i(TAG, "Rotating log to: ${newFile.name}")
        // Flush and close old file safely
        closeCurrentFile()

        // Start new file
        try {
            currentWriter = BufferedWriter(FileWriter(newFile, true))
            isLogging = true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to rotate file", e)
        }
    }

    fun stopLogging() {
        Log.i(TAG, "Stopping logging")
        isLogging = false
        closeCurrentFile()
    }

    private fun closeCurrentFile() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing file", e)
        }
        currentWriter = null
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        writingScope.cancel()
        closeCurrentFile()
    }

    companion object {
        const val TAG = "MirrorPhysService"
    }
}