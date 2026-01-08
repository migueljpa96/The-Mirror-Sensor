package com.mirror.sensor.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper // <--- ADD THIS IMPORT
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mirror.sensor.R
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class PhysicalService : Service(), SensorEventListener, LocationListener {

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    // IO Handling
    private val writingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<String>(capacity = 1000)
    private var currentWriter: BufferedWriter? = null
    private var isLogging = false

    // State
    private var lastConfirmedPosture = "UNKNOWN"
    private var lastConfirmedMotion = "STATIONARY"

    // Accumulators
    private var luxSum = 0f
    private var luxCount = 0
    private var lastLocation: Location? = null
    private var lastLoggedLocation: Location? = null

    // Motion
    private var motionEnergySum = 0f
    private var motionSampleCount = 0
    private var currentAcc = FloatArray(3)
    private var isProximityNear = false
    private var currentPressure = 0f

    // Config
    private val FLUSH_INTERVAL_MS = 5 * 60 * 1000L
    private var lastFlushTime = System.currentTimeMillis()

    inner class LocalBinder : Binder() {
        fun getService(): PhysicalService = this@PhysicalService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        createNotificationChannel()
        // Removed premature startForegroundService

        // Start IO Consumer
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

    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirror Sensor Active")
            .setContentText("Logging physical context...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Mirror Sensor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // --- CONTROL INTERFACE ---

    fun startLogging(file: File) {
        Log.i(TAG, "⚡ Starting Logging to: ${file.name}")

        // Promote to Foreground on Start
        startForegroundService()

        closeCurrentFile()
        try {
            file.parentFile?.mkdirs()
            currentWriter = BufferedWriter(FileWriter(file, true))
            isLogging = true
            registerSensors()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open file: ${file.path}", e)
        }
    }

    fun rotateLog(newFile: File) {
        Log.i(TAG, "🔄 Rotating Log to: ${newFile.name}")
        flushSession("ROTATION")
        closeCurrentFile()
        try {
            newFile.parentFile?.mkdirs()
            currentWriter = BufferedWriter(FileWriter(newFile, true))
            isLogging = true
            luxSum = 0f
            luxCount = 0
        } catch (e: IOException) {
            Log.e(TAG, "Failed to rotate file", e)
        }
    }

    fun stopLogging() {
        Log.i(TAG, "🛑 Stopping Logging")
        flushSession("STOP_TRACKING")
        isLogging = false
        unregisterSensors()
        closeCurrentFile()
        stopForeground(true)
    }

    private fun closeCurrentFile() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (e: Exception) {}
        currentWriter = null
    }

    // --- SENSOR LOGIC ---

    @SuppressLint("MissingPermission")
    private fun registerSensors() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, pressure, SensorManager.SENSOR_DELAY_NORMAL)

        try {
            // FIX: Explicitly use Main Looper to avoid Thread crash
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                10000L,
                20f,
                this,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "GPS Permission Error or Thread Issue", e)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isLogging || event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, currentAcc, 0, 3)
                processMotionLogic(event.values[0], event.values[1], event.values[2])
            }
            Sensor.TYPE_LIGHT -> {
                luxSum += event.values[0]
                luxCount++
            }
            Sensor.TYPE_PRESSURE -> currentPressure = event.values[0]
            Sensor.TYPE_PROXIMITY -> {
                isProximityNear = event.values[0] < event.sensor.maximumRange
            }
        }
    }

    private fun processMotionLogic(x: Float, y: Float, z: Float) {
        val mag = sqrt(x*x + y*y + z*z) - 9.81f
        val energy = abs(mag).toFloat()
        motionEnergySum += energy
        motionSampleCount++

        if (motionSampleCount >= 50) {
            val avgEnergy = motionEnergySum / motionSampleCount
            lastConfirmedMotion = if (avgEnergy > 0.5f) "MOVING" else "STATIONARY"

            val yVal = currentAcc[1]
            val zVal = currentAcc[2]
            lastConfirmedPosture = when {
                isProximityNear -> "POCKET/COVERED"
                zVal < -8.0 -> "FACE_DOWN"
                zVal > 8.0 -> "FACE_UP"
                yVal > 8.0 -> "HAND_UPRIGHT"
                else -> "HANDLING"
            }

            motionEnergySum = 0f
            motionSampleCount = 0

            if (System.currentTimeMillis() - lastFlushTime > FLUSH_INTERVAL_MS) {
                flushSession("HEARTBEAT")
            }
        }
    }

    private fun flushSession(trigger: String) {
        val now = System.currentTimeMillis()
        val entry = JSONObject()
        entry.put("event", "PHYSICAL_CONTEXT")
        entry.put("trigger", trigger)
        entry.put("ts", now)
        entry.put("posture", lastConfirmedPosture)
        entry.put("motion", lastConfirmedMotion)
        entry.put("lux", if (luxCount > 0) luxSum/luxCount else 0)

        lastLocation?.let {
            if (lastLoggedLocation == null || it.distanceTo(lastLoggedLocation!!) > 50 || trigger == "HEARTBEAT") {
                entry.put("lat", it.latitude)
                entry.put("lng", it.longitude)
                lastLoggedLocation = it
            }
        }

        logChannel.trySend(entry.toString())
        lastFlushTime = now
    }

    override fun onLocationChanged(l: Location) { lastLocation = l }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("Deprecated") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}

    override fun onDestroy() {
        super.onDestroy()
        writingScope.cancel()
        stopForeground(true)
    }

    companion object {
        const val TAG = "MirrorPhysService"
        const val CHANNEL_ID = "mirror_sensor_channel"
    }
}