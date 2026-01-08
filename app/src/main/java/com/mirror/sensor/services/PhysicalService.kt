package com.mirror.sensor.services

import android.annotation.SuppressLint
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
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class PhysicalService(private val context: Context) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var tempLogFile: File? = null

    // --- STATE MACHINE ---
    private var sessionStart = System.currentTimeMillis()
    private var lastFlushTime = System.currentTimeMillis()

    // The "Truth" States
    private var lastConfirmedPosture = "UNKNOWN"
    private var lastConfirmedMotion = "STATIONARY"

    // Hysteresis (Debouncing)
    private var pendingPosture = "UNKNOWN"
    private var postureStableStartTime = 0L
    private val POSTURE_STABILITY_MS = 2000L // Must hold for 2s to count

    // Accumulators
    private var luxSum = 0f
    private var luxCount = 0
    private var distanceAccumulated = 0f // Distance since last flush
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

    fun startTracking() {
        Log.d(TAG, "⚡ Physical Context Engine Started")
        tempLogFile = File(context.getExternalFilesDir(null), "temp_physical.jsonl")
        resetSession()

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 20f, this)
        } catch (e: Exception) { Log.e(TAG, "GPS Error", e) }
    }

    fun stopTracking() {
        flushSession("STOP_TRACKING")
        try {
            sensorManager.unregisterListener(this)
            locationManager.removeUpdates(this)
        } catch (e: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

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
                // Instant update for proximity as it overrides posture (Pocket)
                isProximityNear = event.values[0] < event.sensor.maximumRange
                processPostureDebounce()
            }
        }
    }

    private fun processMotionLogic(x: Float, y: Float, z: Float) {
        // 1. Motion Energy
        val mag = sqrt(x*x + y*y + z*z) - 9.81f
        val energy = abs(mag).toFloat()

        motionEnergySum += energy
        motionSampleCount++

        // Evaluate Motion State every ~1 second
        if (motionSampleCount >= 50) {
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

        // 2. Posture Debounce Check (Runs continuously)
        processPostureDebounce()

        // 3. Heartbeat
        if (System.currentTimeMillis() - lastFlushTime > FLUSH_INTERVAL_MS) {
            flushSession("HEARTBEAT")
        }
    }

    private fun processPostureDebounce() {
        val rawPosture = determineSemanticPosture()

        if (rawPosture != pendingPosture) {
            // State changed, start timer
            pendingPosture = rawPosture
            postureStableStartTime = System.currentTimeMillis()
        } else {
            // State is stable, check duration
            val stableDuration = System.currentTimeMillis() - postureStableStartTime
            if (stableDuration > POSTURE_STABILITY_MS) {
                // Confirmed stable change
                if (rawPosture != lastConfirmedPosture) {
                    // Ignore transitions FROM "UNKNOWN" at startup to avoid noise
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
        distanceAccumulated = 0f // Reset local distance accumulator
        // Note: We DO NOT reset lastLoggedLocation here, to track drift across sessions
    }

    @SuppressLint("MissingPermission")
    private fun flushSession(trigger: String) {
        val now = System.currentTimeMillis()
        val duration = (now - sessionStart) / 1000L
        if (duration < 1 && !trigger.startsWith("POSTURE")) return

        val entry = JSONObject()
        entry.put("event_type", "PHYSICAL_CONTEXT")
        entry.put("trigger", trigger)
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now)))
        entry.put("duration_last_state_sec", duration)

        entry.put("posture", lastConfirmedPosture)
        entry.put("motion", lastConfirmedMotion)

        val avgLux = if (luxCount > 0) luxSum / luxCount else 0f
        entry.put("lux_avg", String.format(Locale.US, "%.1f", avgLux)) // Fixed formatting
        entry.put("pressure", String.format(Locale.US, "%.1f", currentPressure))

        val battery = getBatterySnapshot()
        entry.put("battery", battery.first)
        entry.put("charging", battery.second)
        entry.put("ringer", getRingerMode())
        entry.put("network", getNetworkType())

        // SMART LOCATION LOGGING
        // Only log lat/lng if we moved significantly (>50m) OR if it's a critical event
        var shouldLogLocation = false
        try {
            val currentLoc = lastLocation
            if (currentLoc != null) {
                // If we haven't logged yet, or distance > 50m, or it's a MOTION event
                if (lastLoggedLocation == null ||
                    currentLoc.distanceTo(lastLoggedLocation!!) > 50 ||
                    trigger.contains("MOTION") ||
                    trigger == "HEARTBEAT") {

                    entry.put("lat", currentLoc.latitude)
                    entry.put("lng", currentLoc.longitude)
                    entry.put("spd", currentLoc.speed)
                    entry.put("acc", currentLoc.accuracy)

                    lastLoggedLocation = currentLoc
                    shouldLogLocation = true
                }
            }
        } catch (e: Exception) {}

        if (!shouldLogLocation) {
            // Just log the local delta if we aren't sending full coords
            entry.put("dist_delta", String.format("%.0f", distanceAccumulated))
        }

        try {
            FileOutputStream(tempLogFile, true).use { it.write((entry.toString() + "\n").toByteArray()) }
            Log.d(TAG, "📦 Flushed: $trigger")
        } catch (e: Exception) { Log.e(TAG, "Write error", e) }

        lastFlushTime = now
        resetSession()
    }

    private fun getBatterySnapshot(): Pair<Int, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return Pair(if (level > 0) (level * 100 / scale) else 0, status == BatteryManager.BATTERY_STATUS_CHARGING)
    }

    private fun getRingerMode() = when (audioManager.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "SILENT"
        AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
        else -> "NORMAL"
    }

    private fun getNetworkType(): String {
        val net = connectivityManager.activeNetwork ?: return "OFFLINE"
        val cap = connectivityManager.getNetworkCapabilities(net) ?: return "OFFLINE"
        return if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "WIFI" else "CELL"
    }

    override fun onLocationChanged(l: Location) {
        if (lastLocation != null) distanceAccumulated += l.distanceTo(lastLocation!!)
        lastLocation = l
    }

    fun rotateLogFile(masterTimestamp: String) {
        flushSession("ROTATION")
        if (tempLogFile == null || !tempLogFile!!.exists() || tempLogFile!!.length() == 0L) return
        val finalFile = File(context.getExternalFilesDir(null), "PHYSICAL_$masterTimestamp.jsonl")
        if (tempLogFile!!.renameTo(finalFile)) tempLogFile = File(context.getExternalFilesDir(null), "temp_physical.jsonl")
    }
    fun cleanup() { stopTracking(); tempLogFile?.delete() }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("Deprecated") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}

    companion object { const val TAG = "TheMirrorPhysical" }
}