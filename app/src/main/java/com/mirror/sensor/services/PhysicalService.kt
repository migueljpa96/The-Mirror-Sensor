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
    private var tempLogFile: File? = null
    private var lastLogTime = 0L
    private val LOG_INTERVAL_MS = 5000 // Log a snapshot every 5 seconds

    // State Variables
    private var currentMaxAcceleration = 0.0f
    private var currentLightLevel = -1f
    private var isProximityNear = false
    private var totalSteps = -1f

    fun startTracking() {
        // Initialize the temp file
        tempLogFile = File(context.getExternalFilesDir(null), "temp_physical.jsonl")

        // Register Hardware Sensors
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        // Start GPS
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 300000L, 500f, this)
            Log.d(TAG, "Physical Sensors Active")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "GPS Error", e)
        }
    }

    // --- NEW: EXPLICIT ROTATION METHOD ---
    // Called by HolisticSensorService with the Master Timestamp
    fun rotateLogFile(masterTimestamp: String) {
        if (tempLogFile == null || !tempLogFile!!.exists() || tempLogFile!!.length() == 0L) {
            return
        }

        // Apply EXACT SAME ID as Audio
        val finalName = "PHYSICAL_$masterTimestamp.jsonl"
        val finalFile = File(context.getExternalFilesDir(null), finalName)

        try {
            if (tempLogFile!!.renameTo(finalFile)) {
                Log.d(TAG, "✅ Physical Log Synced: $finalName")
                // Start fresh temp file
                tempLogFile = File(context.getExternalFilesDir(null), "temp_physical.jsonl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Rotation failed", e)
        }
    }

    fun stopTracking() {
        try {
            sensorManager.unregisterListener(this)
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    // --- SENSOR LOGIC (Standard) ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z) - 9.81f
                val absMag = abs(magnitude)
                if (absMag > currentMaxAcceleration) currentMaxAcceleration = absMag.toFloat()

                val now = System.currentTimeMillis()
                if (now - lastLogTime > LOG_INTERVAL_MS) logSnapshot(now)
            }
            Sensor.TYPE_LIGHT -> currentLightLevel = event.values[0]
            Sensor.TYPE_PROXIMITY -> isProximityNear = event.values[0] < event.sensor.maximumRange
            Sensor.TYPE_STEP_COUNTER -> totalSteps = event.values[0]
        }
    }

    @SuppressLint("MissingPermission")
    private fun logSnapshot(now: Long) {
        val entry = JSONObject()
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
            Date()
        ))
        entry.put("motion_intensity", String.format("%.2f", currentMaxAcceleration))
        entry.put("light_lux", currentLightLevel)
        entry.put("proximity", if (isProximityNear) "NEAR" else "FAR")
        entry.put("steps_total", totalSteps)

        try {
            val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) {
                entry.put("lat", loc.latitude)
                entry.put("lng", loc.longitude)
            }
        } catch (e: Exception) { /* Ignore */ }

        // Battery Check
        try {
            val batteryStatus = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { context.registerReceiver(null, it) }
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else -1
            entry.put("battery_level", pct)
        } catch (e: Exception) { /* Ignore */ }

        // Append to file
        try {
            FileOutputStream(tempLogFile, true).use { stream ->
                stream.write((entry.toString() + "\n").toByteArray())
            }
            currentMaxAcceleration = 0f
            lastLogTime = now
        } catch (e: Exception) { Log.e(TAG, "Write error", e) }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onLocationChanged(l: Location) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    companion object { const val TAG = "TheMirrorPhysical" }
}