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
    private val LOG_INTERVAL_MS = 5000

    // State
    private var currentMaxAcceleration = 0.0f
    private var currentLightLevel = -1f
    private var isProximityNear = false
    private var totalSteps = -1f

    fun startTracking() {
        Log.d(TAG, "⚡ Physical Tracking Started")
        tempLogFile = File(context.getExternalFilesDir(null), "temp_physical.jsonl")

        // Sensors
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        // GPS
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 300000L, 500f, this)
        } catch (e: SecurityException) { Log.e(TAG, "Loc perm missing", e) }
        catch (e: Exception) { Log.e(TAG, "GPS Error", e) }
    }

    fun stopTracking() {
        try {
            sensorManager.unregisterListener(this)
            locationManager.removeUpdates(this)
        } catch (e: Exception) {}
        Log.d(TAG, "🛑 Physical Tracking Stopped")
    }

    fun rotateLogFile(masterTimestamp: String) {
        if (tempLogFile == null || !tempLogFile!!.exists() || tempLogFile!!.length() == 0L) return

        val finalName = "PHYSICAL_$masterTimestamp.jsonl"
        val finalFile = File(context.getExternalFilesDir(null), finalName)

        if (tempLogFile!!.renameTo(finalFile)) {
            Log.d(TAG, "✅ Physical Log Sealed: $finalName")
            tempLogFile = File(context.getExternalFilesDir(null), "temp_physical.jsonl")
        }
    }

    // Called by MasterService.onDestroy
    fun cleanup() {
        stopTracking()
        if (tempLogFile?.exists() == true) {
            val deleted = tempLogFile?.delete() ?: false
            Log.d(TAG, "🧹 Temp Physical File Deleted: $deleted")
        }
    }

    // --- SENSOR LOGIC ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val mag = sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2]) - 9.81f
                val absMag = abs(mag).toFloat()
                if (absMag > currentMaxAcceleration) currentMaxAcceleration = absMag

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
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        entry.put("motion_intensity", String.format("%.2f", currentMaxAcceleration))
        entry.put("light_lux", currentLightLevel)
        entry.put("steps", totalSteps)

        try {
            val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) {
                entry.put("lat", loc.latitude)
                entry.put("lng", loc.longitude)
            }
        } catch (e: Exception) {}

        try {
            FileOutputStream(tempLogFile, true).use { it.write((entry.toString() + "\n").toByteArray()) }
            currentMaxAcceleration = 0f
            lastLogTime = now
        } catch (e: Exception) {}
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onLocationChanged(l: Location) {}
    @Deprecated("Deprecated") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    companion object { const val TAG = "TheMirrorPhysical" }
}