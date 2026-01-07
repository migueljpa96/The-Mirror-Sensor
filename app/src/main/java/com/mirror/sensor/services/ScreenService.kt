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

/**
 * Handles "Digital Context" (App Usage/Focus).
 * Refactored to use "SCREEN" naming convention to match DataUploadWorker.
 */
class ScreenService(private val context: Context) {

    private var tempFile: File? = null
    private var lastAppPackage: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // Poll every 2 seconds
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkCurrentApp()
            handler.postDelayed(this, 2000)
        }
    }

    fun startTracking() {
        Log.d(TAG, "⚡ Screen Tracking Started")
        isRunning = true
        // RESTORED: Naming convention matches DataUploadWorker expectations
        tempFile = File(context.getExternalFilesDir(null), "temp_screen.jsonl")

        handler.post(pollRunnable)
    }

    fun stopTracking() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "🛑 Screen Tracking Stopped")
    }

    // Called by MasterService during rotation
    fun rotateLogFile(masterTimestamp: String) {
        if (tempFile == null || !tempFile!!.exists() || tempFile!!.length() == 0L) return

        // RESTORED: Prefix "SCREEN_" ensures worker routes to 'screen_logs' folder
        val finalName = "SCREEN_$masterTimestamp.jsonl"
        val finalFile = File(context.getExternalFilesDir(null), finalName)

        if (tempFile!!.renameTo(finalFile)) {
            Log.d(TAG, "✅ Screen Log Sealed: $finalName")
            tempFile = File(context.getExternalFilesDir(null), "temp_screen.jsonl")
        }
    }

    // Called by MasterService.onDestroy
    fun cleanup() {
        stopTracking()
        if (tempFile?.exists() == true) {
            val deleted = tempFile?.delete() ?: false
            Log.d(TAG, "🧹 Temp Screen File Deleted: $deleted")
        }
    }

    private fun checkCurrentApp() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val events = usm.queryEvents(time - 5000, time)
        val event = UsageEvents.Event()

        var newPkg = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                newPkg = event.packageName
            }
        }

        if (newPkg.isNotEmpty() && newPkg != lastAppPackage) {
            lastAppPackage = newPkg
            logUsage(newPkg)
        }
    }

    private fun logUsage(pkg: String) {
        val entry = JSONObject()
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        entry.put("app_package", pkg)
        entry.put("event", "FOREGROUND")

        try {
            FileOutputStream(tempFile, true).use { it.write((entry.toString() + "\n").toByteArray()) }
        } catch (e: Exception) { Log.e(TAG, "Write error", e) }
    }

    companion object { const val TAG = "TheMirrorScreen" }
}