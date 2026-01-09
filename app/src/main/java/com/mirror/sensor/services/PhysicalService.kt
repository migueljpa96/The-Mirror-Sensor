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
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
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

    private val writingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<String>(capacity = 1000)
    private var currentWriter: BufferedWriter? = null
    private var isLogging = false

    // --- STATE MACHINE ---
    private var lastConfirmedPosture = "UNKNOWN"
    private var lastConfirmedMotion = "STATIONARY"
    private var lastStateChangeTime = System.currentTimeMillis()

    // Stability Buffers
    private var pendingPosture = "UNKNOWN"
    private var postureStabilityCount = 0
    private var pendingMotion = "STATIONARY"
    private var motionStabilityCount = 0

    // Constants
    private val STABILITY_THRESHOLD = 3
    private val SAMPLE_BATCH_SIZE = 50
    private val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    private var lastLogTime = System.currentTimeMillis()

    // Sensor Accumulators
    private var luxSum = 0f
    private var luxCount = 0
    private var motionEnergySum = 0f
    private var motionSampleCount = 0
    private var currentAcc = FloatArray(3)
    private var isProximityNear = false

    // Deduplication State
    private var lastLocation: Location? = null // <--- RESTORED THIS
    private var lastLoggedLocation: Location? = null
    private var lastLoggedBattery = -1
    private var lastLoggedNetwork = ""

    inner class LocalBinder : Binder() {
        fun getService(): PhysicalService = this@PhysicalService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()

        writingScope.launch {
            for (line in logChannel) {
                try {
                    currentWriter?.write(line)
                    currentWriter?.newLine()
                    currentWriter?.flush()
                } catch (e: IOException) { Log.e(TAG, "Write failed", e) }
            }
        }
    }

    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirror Sensor Active").setContentText("Logging physical context...")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(1001, notification)
        } catch (e: Exception) { Log.e(TAG, "Failed to start foreground service", e) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Mirror Sensor Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    fun startLogging(file: File) {
        Log.i(TAG, "⚡ Starting Logging to: ${file.name}")
        startForegroundService()
        closeCurrentFile()
        try {
            file.parentFile?.mkdirs()
            currentWriter = BufferedWriter(FileWriter(file, true))
            isLogging = true

            // Reset State
            lastConfirmedPosture = "UNKNOWN"
            lastStateChangeTime = System.currentTimeMillis()
            lastLogTime = System.currentTimeMillis()
            pendingPosture = "UNKNOWN"
            postureStabilityCount = 0

            // Reset Dedup
            lastLoggedLocation = null
            lastLoggedBattery = -1
            lastLoggedNetwork = ""

            registerSensors()
            flushLog("SESSION_START", isInitial = true)
        } catch (e: IOException) { Log.e(TAG, "Failed to open file", e) }
    }

    fun rotateLog(newFile: File) {
        Log.i(TAG, "🔄 Rotating Log")
        flushLog("ROTATION_BOUNDARY")
        closeCurrentFile()
        try {
            newFile.parentFile?.mkdirs()
            currentWriter = BufferedWriter(FileWriter(newFile, true))
            isLogging = true
            luxSum = 0f; luxCount = 0
            flushLog("ROTATION_START")
        } catch (e: IOException) { Log.e(TAG, "Failed to rotate", e) }
    }

    fun stopLogging() {
        Log.i(TAG, "🛑 Stopping Logging")
        flushLog("SESSION_END")
        isLogging = false
        unregisterSensors()
        closeCurrentFile()
        stopForeground(true)
    }

    private fun closeCurrentFile() {
        try { currentWriter?.flush(); currentWriter?.close() } catch (e: Exception) {}
        currentWriter = null
    }

    @SuppressLint("MissingPermission")
    private fun registerSensors() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL)

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 20f, this, Looper.getMainLooper())
        } catch (e: Exception) { Log.e(TAG, "GPS Error", e) }
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
            Sensor.TYPE_LIGHT -> { luxSum += event.values[0]; luxCount++ }
            Sensor.TYPE_PROXIMITY -> isProximityNear = event.values[0] < event.sensor.maximumRange
        }
    }

    private fun processMotionLogic(x: Float, y: Float, z: Float) {
        val mag = sqrt(x * x + y * y + z * z) - 9.81f
        val energy = abs(mag).toFloat()
        motionEnergySum += energy
        motionSampleCount++

        if (motionSampleCount >= SAMPLE_BATCH_SIZE) {
            val avgEnergy = motionEnergySum / motionSampleCount

            val detectedMotion = when {
                avgEnergy > 3.0f -> "HIGH_ACTIVITY"
                avgEnergy > 0.5f -> "MOVING"
                else -> "STATIONARY"
            }

            val detectedPosture = when {
                isProximityNear -> "POCKET"
                z < -8.5 -> "FACE_DOWN"
                z > 8.5 -> "FACE_UP"
                y > 7.0 -> "PORTRAIT"
                abs(x) > 7.0 -> "LANDSCAPE"
                else -> "HANDLING"
            }

            val now = System.currentTimeMillis()
            var stateChanged = false

            // Posture Filter
            if (detectedPosture == pendingPosture) {
                postureStabilityCount++
            } else {
                pendingPosture = detectedPosture
                postureStabilityCount = 1
            }

            if (postureStabilityCount >= STABILITY_THRESHOLD && pendingPosture != lastConfirmedPosture) {
                val durationSec = (now - lastStateChangeTime) / 1000
                flushLog("POSTURE_CHANGE", durationSec, nextPosture = pendingPosture)

                lastConfirmedPosture = pendingPosture
                lastStateChangeTime = now
                stateChanged = true
            }

            // Motion Filter
            if (detectedMotion == pendingMotion) {
                motionStabilityCount++
            } else {
                pendingMotion = detectedMotion
                motionStabilityCount = 1
            }

            if (motionStabilityCount >= STABILITY_THRESHOLD && pendingMotion != lastConfirmedMotion) {
                lastConfirmedMotion = pendingMotion
            }

            // Heartbeat
            if (!stateChanged && (now - lastLogTime > HEARTBEAT_INTERVAL_MS)) {
                val durationSec = (now - lastStateChangeTime) / 1000
                flushLog("HEARTBEAT", durationSec)
            }

            motionEnergySum = 0f
            motionSampleCount = 0
        }
    }

    private fun flushLog(reason: String, durationLastStateSec: Long = 0, nextPosture: String? = null, isInitial: Boolean = false) {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        val entry = JSONObject()
        entry.put("timestamp", sdf.format(Date(now)))
        entry.put("event_type", "PHYSICAL_CONTEXT")
        entry.put("reason", reason)

        if (!isInitial) {
            entry.put("prev_posture", lastConfirmedPosture)
            entry.put("prev_duration_sec", durationLastStateSec)
            val avgLux = if (luxCount > 0) luxSum / luxCount else 0f
            if (avgLux > 5.0) {
                entry.put("prev_avg_lux", String.format(Locale.US, "%.1f", avgLux))
            }
        }

        entry.put("curr_posture", nextPosture ?: lastConfirmedPosture)
        entry.put("curr_motion", lastConfirmedMotion)

        val (batLvl, isCharging) = getBatteryInfo()
        if (reason == "HEARTBEAT" || reason == "SESSION_START" || abs(batLvl - lastLoggedBattery) >= 5) {
            entry.put("curr_battery", batLvl)
            entry.put("is_charging", isCharging)
            lastLoggedBattery = batLvl
        }

        val netType = getNetworkType()
        if (reason == "HEARTBEAT" || reason == "SESSION_START" || netType != lastLoggedNetwork) {
            entry.put("curr_network", netType)
            lastLoggedNetwork = netType
        }

        // Location Dedup: Only log if moved > 50m or on Heartbeat/Start
        val currentLocation = lastLocation
        if (currentLocation != null) {
            val dist = lastLoggedLocation?.distanceTo(currentLocation) ?: 9999f
            if (reason == "HEARTBEAT" || reason == "SESSION_START" || dist > 50) {
                entry.put("lat", currentLocation.latitude)
                entry.put("lng", currentLocation.longitude)
                entry.put("speed", currentLocation.speed)
                lastLoggedLocation = currentLocation
            }
        }

        logChannel.trySend(entry.toString())
        lastLogTime = now
        luxSum = 0f
        luxCount = 0
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(lvl, isCharging)
    }

    private fun getNetworkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "NONE"
        val caps = cm.getNetworkCapabilities(net) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
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