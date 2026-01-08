package com.mirror.sensor.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Binder
import android.os.Bundle
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class PhysicalService : Service(), SensorEventListener, LocationListener {

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private lateinit var connectivityManager: ConnectivityManager

    // IO Handling
    private val writingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<String>(capacity = 1000)
    private var currentWriter: BufferedWriter? = null
    private var isLogging = false

    // --- SENSOR STATE ---
    private var sessionStart = System.currentTimeMillis()
    private var lastFlushTime = System.currentTimeMillis()
    private var lastConfirmedPosture = "UNKNOWN"
    private var lastConfirmedMotion = "STATIONARY"

    // Hysteresis
    private var pendingPosture = "UNKNOWN"
    private var postureStableStartTime = 0L
    private val POSTURE_STABILITY_MS = 2000L

    // Accumulators
    private var luxSum = 0f
    private var luxCount = 0
    private var distanceAccumulated = 0f
    private var lastLocation: Location? = null
    private var lastLoggedLocation: Location? = null

    // Motion Filtering
    private var motionEnergySum = 0f
    private var motionSampleCount = 0
    private var activeMotionStartTime = 0L

    // Raw Sensor Values
    private var currentAcc = FloatArray(3)
    private var isProximityNear = false
    private var currentPressure = 0f

    private val FLUSH_INTERVAL_MS = 5 * 60 * 1000L
    private val MOTION_THRESHOLD = 0.5f
    private val DURATION_THRESHOLD_ACTIVE = 5000L

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Start Consumer Loop
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

    // --- CONTROL INTERFACE (Called by SessionManager) ---

    fun startLogging(file: File) {
        Log.i(TAG, "⚡ Starting Logging to: ${file.name}")
        closeCurrentFile()
        try {
            // Ensure parent dir exists
            file.parentFile?.mkdirs()
            currentWriter = BufferedWriter(FileWriter(file, true))
            isLogging = true

            // Reset Session State for clean metrics per shard
            resetSession()
            registerSensors()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open file: ${file.path}", e)
        }
    }

    fun rotateLog(newFile: File) {
        Log.i(TAG, "🔄 Rotating Log to: ${newFile.name}")
        // 1. Flush any remaining state (Heartbeat)
        flushSession("ROTATION")

        // 2. Switch Files safely
        closeCurrentFile()
        try {
            newFile.parentFile?.mkdirs()
            currentWriter = BufferedWriter(FileWriter(newFile, true))
            isLogging = true
            // Note: We do NOT reset session state (posture, location) here
            // to maintain continuity across shard boundaries if needed,
            // but metrics (lux count) should probably reset for the new shard statistics.
            resetSession()
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
    }

    private fun closeCurrentFile() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing file", e)
        }
        currentWriter = null
    }

    // --- SENSOR LOGIC ---

    @SuppressLint("MissingPermission")
    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        try {
            // Request GPS if available (Permission must be handled by UI/Manifest)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 20f, this)
        } catch (e: Exception) { Log.e(TAG, "GPS Error (Permission?)", e) }
    }

    private fun unregisterSensors() {
        try {
            sensorManager.unregisterListener(this)
            locationManager.removeUpdates(this)
        } catch (e: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isLogging) return

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
                processPostureDebounce()
            }
        }
    }

    private fun processMotionLogic(x: Float, y: Float, z: Float) {
        val mag = sqrt(x*x + y*y + z*z) - 9.81f
        val energy = abs(mag).toFloat()

        motionEnergySum += energy
        motionSampleCount++

        if (motionSampleCount >= 50) { // ~1 sec at 50Hz (GAME delay)
            val avgEnergy = motionEnergySum / motionSampleCount
            val instantState = if (avgEnergy > MOTION_THRESHOLD) "MOVING" else "STATIONARY"

            if (instantState == "MOVING") {
                if (activeMotionStartTime == 0L) activeMotionStartTime = System.currentTimeMillis()
                val duration = System.currentTimeMillis() - activeMotionStartTime
                if (duration > DURATION_THRESHOLD_ACTIVE && lastConfirmedMotion != "ACTIVE") {
                    flushSession("MOTION_START")
                    lastConfirmedMotion = "ACTIVE"
                }
            } else {
                activeMotionStartTime = 0L
                if (lastConfirmedMotion == "ACTIVE") {
                    flushSession("MOTION_STOP")
                    lastConfirmedMotion = "STATIONARY"
                }
            }
            motionEnergySum = 0f
            motionSampleCount = 0
        }

        processPostureDebounce()

        if (System.currentTimeMillis() - lastFlushTime > FLUSH_INTERVAL_MS) {
            flushSession("HEARTBEAT")
        }
    }

    private fun processPostureDebounce() {
        val rawPosture = determineSemanticPosture()
        if (rawPosture != pendingPosture) {
            pendingPosture = rawPosture
            postureStableStartTime = System.currentTimeMillis()
        } else {
            if (System.currentTimeMillis() - postureStableStartTime > POSTURE_STABILITY_MS) {
                if (rawPosture != lastConfirmedPosture) {
                    if (lastConfirmedPosture != "UNKNOWN") {
                        flushSession("POSTURE: $lastConfirmedPosture -> $rawPosture")
                    }
                    lastConfirmedPosture = rawPosture
                }
            }
        }
    }

    private fun determineSemanticPosture(): String {
        val x = currentAcc[0]; val y = currentAcc[1]; val z = currentAcc[2]
        if (isProximityNear) {
            return when {
                y > 7.0 || y < -7.0 -> "POCKET_UPRIGHT"
                z < -7.0 -> "TABLE_FACEDOWN"
                else -> "COVERED"
            }
        }
        return when {
            z < -8.0 -> "FACE_DOWN"
            z > 8.0 -> "FACE_UP"
            y > 8.0 -> "HAND_UPRIGHT"
            abs(x) > 8.0 -> "LANDSCAPE"
            else -> "HANDLING"
        }
    }

    private fun resetSession() {
        sessionStart = System.currentTimeMillis()
        luxSum = 0f
        luxCount = 0
        distanceAccumulated = 0f
    }

    private fun flushSession(trigger: String) {
        val now = System.currentTimeMillis()
        // Format Entry
        val entry = JSONObject()
        entry.put("event_type", "PHYSICAL_CONTEXT")
        entry.put("trigger", trigger)
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now)))

        entry.put("posture", lastConfirmedPosture)
        entry.put("motion", lastConfirmedMotion)

        val avgLux = if (luxCount > 0) luxSum / luxCount else 0f
        entry.put("lux_avg", String.format(Locale.US, "%.1f", avgLux))

        // Location (if significant)
        lastLocation?.let { loc ->
            if (lastLoggedLocation == null || loc.distanceTo(lastLoggedLocation!!) > 50 || trigger == "HEARTBEAT") {
                entry.put("lat", loc.latitude)
                entry.put("lng", loc.longitude)
                entry.put("acc", loc.accuracy)
                lastLoggedLocation = loc
            }
        }

        // Send to Channel
        logChannel.trySend(entry.toString())
        lastFlushTime = now
        resetSession() // Reset accumulators
    }

    override fun onDestroy() {
        super.onDestroy()
        writingScope.cancel()
        unregisterSensors()
        closeCurrentFile()
    }

    // Location Listener stubs
    override fun onLocationChanged(l: Location) { lastLocation = l }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("Deprecated") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}

    companion object { const val TAG = "MirrorPhysService" }
}