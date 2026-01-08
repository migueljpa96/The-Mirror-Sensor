package com.mirror.sensor.services

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenService(private val context: Context) {

    private var tempFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // Session State
    private var currentPackage = "NONE"
    private var sessionStartTime = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkAppSwitch()
            handler.postDelayed(this, 2000)
        }
    }

    fun startTracking() {
        Log.d(TAG, "⚡ Screen Session-Tracker Started")
        isRunning = true
        tempFile = File(context.getExternalFilesDir(null), "temp_screen.jsonl")

        currentPackage = "NONE"
        sessionStartTime = System.currentTimeMillis()

        handler.post(pollRunnable)
    }

    fun stopTracking() {
        // Close final session
        logSession(currentPackage, sessionStartTime, System.currentTimeMillis())
        isRunning = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun checkAppSwitch() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5000, now)
        val event = UsageEvents.Event()

        var detectedPkg = ""
        var lastEventTime = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                detectedPkg = event.packageName
                lastEventTime = event.timeStamp
            }
        }

        if (detectedPkg.isNotEmpty() && detectedPkg != currentPackage) {
            // Close old
            if (currentPackage != "NONE") {
                logSession(currentPackage, sessionStartTime, lastEventTime)
            }
            // Start new
            currentPackage = detectedPkg
            sessionStartTime = lastEventTime
            Log.v(TAG, "📱 App Switch: $detectedPkg")
        }
    }

    private fun logSession(pkg: String, start: Long, end: Long) {
        val duration = (end - start) / 1000L
        if (duration < 1) return

        val entry = JSONObject()
        entry.put("event_type", "DIGITAL_SESSION")
        entry.put("app_package", pkg)
        entry.put("timestamp_start", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(start)))
        entry.put("duration_sec", duration)

        try {
            FileOutputStream(tempFile, true).use { it.write((entry.toString() + "\n").toByteArray()) }
        } catch (e: Exception) { Log.e(TAG, "Write error", e) }
    }

    fun rotateLogFile(masterTimestamp: String) {
        if (tempFile == null || !tempFile!!.exists() || tempFile!!.length() == 0L) return
        val finalFile = File(context.getExternalFilesDir(null), "SCREEN_$masterTimestamp.jsonl")
        if (tempFile!!.renameTo(finalFile)) {
            tempFile = File(context.getExternalFilesDir(null), "temp_screen.jsonl")
        }
    }

    fun cleanup() {
        stopTracking()
        tempFile?.delete()
    }

    companion object { const val TAG = "TheMirrorScreen" }
}