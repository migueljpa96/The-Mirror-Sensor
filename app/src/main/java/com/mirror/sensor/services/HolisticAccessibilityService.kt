package com.mirror.sensor.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HolisticAccessibilityService : AccessibilityService() {

    private var tempLogFile: File? = null
    private var lastCapturedText: String = ""
    private var lastCaptureTime: Long = 0

    // --- RECEIVER ---
    private val rotationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.mirror.sensor.ROTATE_COMMAND") {
                val timestamp = intent.getStringExtra("TIMESTAMP_ID")
                if (timestamp != null) {
                    rotateLogFile(timestamp)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Screen Sensor Connected")
        tempLogFile = File(getExternalFilesDir(null), "temp_screen.jsonl")

        val filter = IntentFilter("com.mirror.sensor.ROTATE_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rotationReceiver, filter, RECEIVER_NOT_EXPORTED)
        }
    }

    private fun rotateLogFile(masterTimestamp: String) {
        if (tempLogFile == null || !tempLogFile!!.exists() || tempLogFile!!.length() == 0L) return

        val finalName = "SCREEN_$masterTimestamp.jsonl"
        val finalFile = File(getExternalFilesDir(null), finalName)

        if (tempLogFile!!.renameTo(finalFile)) {
            Log.d(TAG, "✅ Screen Synced: $finalName")
            tempLogFile = File(getExternalFilesDir(null), "temp_screen.jsonl")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = System.currentTimeMillis()

        // Increased cooldown to 3 seconds to reduce log volume further
        if (now - lastCaptureTime < 3000) return

        // We only care about window state changes (App switching) or Content Changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Ignore system noise
        if (packageName == "com.android.systemui") return

        // --- SIMPLIFIED CAPTURE ---
        // Instead of reading the whole screen, we just log the App Name.
        // If you want to log specific text (like a URL or Chat Name), we can be selective later.

        if (packageName != lastCapturedText) { // Reuse variable for package name deduplication
            lastCapturedText = packageName
            lastCaptureTime = now

            // COMMENTED OUT: Deep Text Extraction
            /*
            val source = event.source
            val fullText = extractText(source)
            if (fullText.isNotEmpty()) {
                 writeLog(packageName, fullText)
            }
            */

            // NEW: Simple App Logging
            Log.d(TAG, "👁️ App Switch: $packageName") // Log the switch
            writeLog(packageName, "App Active")
        }
    }

    override fun onInterrupt() {
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        if (!node.text.isNullOrEmpty()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(node.contentDescription).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            sb.append(extractText(child))
            child?.recycle()
        }
        return sb.toString().trim()
    }

    private fun writeLog(pkg: String, text: String) {
        if (tempLogFile == null) return
        val entry = JSONObject()
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        entry.put("package", pkg)
        entry.put("text", text)
        try {
            FileOutputStream(tempLogFile, true).use { it.write((entry.toString() + "\n").toByteArray()) }
        } catch (e: Exception) { Log.e(TAG, "Write error", e) }
    }

    override fun onDestroy() {
        try { unregisterReceiver(rotationReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object { const val TAG = "TheMirrorEye" }
}