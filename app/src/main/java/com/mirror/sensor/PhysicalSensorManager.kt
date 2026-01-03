package com.mirror.sensor

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
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class PhysicalSensorManager(private val context: Context) : SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Safely get these services (they can be null on some weird devices)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var tempLogFile: File? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var rotationJob: Job? = null
    private val ROTATION_INTERVAL_MS = 10 * 60 * 1000L

    private var lastLogTime = 0L
    private val LOG_INTERVAL_MS = 5000

    private var currentMaxAcceleration = 0.0f
    private var currentLightLevel = -1f
    private var isProximityNear = false
    private var totalSteps = -1f

    fun startTracking() {
        tempLogFile = File(context.getExternalFilesDir(null), "temp_physical.jsonl")

        rotationJob = scope.launch {
            while (isActive) {
                delay(ROTATION_INTERVAL_MS)
                rotateLogFile()
            }
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 300000L, 500f, this)
            Log.d(TAG, "Sensors Started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "GPS Error", e)
        }
    }

    fun stopTracking() {
        try {
            sensorManager.unregisterListener(this)
            locationManager.removeUpdates(this)
            rotationJob?.cancel()
            rotateLogFile()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sensors", e)
        }
        Log.d(TAG, "Sensors Stopped")
    }

    private fun rotateLogFile() {
        if (tempLogFile == null || !tempLogFile!!.exists() || tempLogFile!!.length() == 0L) return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val finalName = "PHYSICAL_$timeStamp.jsonl"
        val finalFile = File(context.getExternalFilesDir(null), finalName)

        try {
            if (tempLogFile!!.renameTo(finalFile)) {
                Log.d(TAG, "Rotated Physical Log: $finalName")
                tempLogFile = File(context.getExternalFilesDir(null), "temp_physical.jsonl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Rotation failed", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x*x + y*y + z*z) - 9.81f
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
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

        // --- 1. SENSORS ---
        entry.put("motion_intensity", String.format("%.2f", currentMaxAcceleration))
        entry.put("light_lux", currentLightLevel)
        entry.put("proximity", if (isProximityNear) "NEAR" else "FAR")
        entry.put("steps_total", totalSteps)

        // --- 2. LOCATION ---
        try {
            val lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastLocation != null) {
                entry.put("lat", lastLocation.latitude)
                entry.put("lng", lastLocation.longitude)
            } else {
                entry.put("lat", "unknown")
                entry.put("lng", "unknown")
            }
        } catch (e: Exception) {
            entry.put("lat", "error")
        }

        // --- 3. BATTERY (Safe Mode) ---
        try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (scale > 0) (level * 100 / scale.toFloat()) else -1
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            entry.put("battery_level", batteryPct)
            entry.put("is_charging", isCharging)
        } catch (e: Exception) {
            entry.put("battery_level", "error")
        }

        // --- 4. NETWORK (Safe Mode) ---
        try {
            if (connectivityManager != null) {
                val activeNetwork = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                val networkType = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                    else -> "OFFLINE"
                }
                entry.put("network_type", networkType)
            } else {
                entry.put("network_type", "unknown")
            }
        } catch (e: Exception) {
            entry.put("network_type", "permission_error") // Will catch if you forgot Manifest
        }

        // --- 5. AUDIO OUTPUT (Safe Mode) ---
        try {
            if (audioManager != null) {
                val isHeadset = audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
                entry.put("headphones_connected", isHeadset)
            }
        } catch (e: Exception) {
            entry.put("headphones_connected", false)
        }

        try {
            FileOutputStream(tempLogFile, true).use { stream ->
                stream.write((entry.toString() + "\n").toByteArray())
            }
            currentMaxAcceleration = 0f
            lastLogTime = now
        } catch (e: Exception) { Log.e(TAG, "Write failed", e) }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onLocationChanged(l: Location) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    companion object { const val TAG = "TheMirrorPhysical" }
}