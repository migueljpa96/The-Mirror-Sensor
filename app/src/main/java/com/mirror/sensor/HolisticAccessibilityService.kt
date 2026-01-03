package com.mirror.sensor

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HolisticAccessibilityService : AccessibilityService() {

    private var lastCapturedText: String = ""
    private var lastCaptureTime: Long = 0
    private val CAPTURE_COOLDOWN_MS = 1000

    // Active writing file
    private var tempLogFile: File? = null

    // Rotation Logic
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val ROTATION_INTERVAL_MS = 10 * 60 * 1000L // 10 Minutes

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "The Eye is Open: Service Connected")

        // Start Writing to Temp
        tempLogFile = File(getExternalFilesDir(null), "temp_screen.jsonl")

        // Start Rotation Loop
        startRotationLoop()
    }

    private fun startRotationLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(ROTATION_INTERVAL_MS)
                rotateLogFile()
            }
        }
    }

    private fun rotateLogFile() {
        if (tempLogFile == null || !tempLogFile!!.exists() || tempLogFile!!.length() == 0L) return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val finalName = "SCREEN_$timeStamp.jsonl"
        val finalFile = File(getExternalFilesDir(null), finalName)

        try {
            // Rename temp -> timestamped
            val success = tempLogFile!!.renameTo(finalFile)
            if (success) {
                Log.d(TAG, "Rotated Screen Log: $finalName")
                // Re-create empty temp file object for next write
                tempLogFile = File(getExternalFilesDir(null), "temp_screen.jsonl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate screen log", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) return

        val source = event.source ?: return
        val capturedText = extractText(source)

        if (capturedText.isNotEmpty() && capturedText != lastCapturedText) {
            lastCapturedText = capturedText
            lastCaptureTime = now
            val packageName = event.packageName?.toString() ?: "Unknown"

            writeLogToDisk(packageName, capturedText)

            Log.i(TAG, "CAPTURED [$packageName]: ${capturedText.take(50)}...")
        }
    }

    private fun writeLogToDisk(packageName: String, text: String) {
        if (tempLogFile == null) return

        try {
            val entry = JSONObject()
            entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            entry.put("package", packageName)
            entry.put("text", text)

            val line = entry.toString() + "\n"

            // Append to TEMP file
            FileOutputStream(tempLogFile, true).use { stream ->
                stream.write(line.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write error", e)
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val sb = StringBuilder()

        // Helper to check if text is "Garbage"
        fun isNoise(s: CharSequence?): Boolean {
            if (s.isNullOrEmpty()) return true
            val str = s.toString()
            // The Blacklist: Ignore our own app name and generic system labels
            return str.contains("The Mirror Sensor") ||
                    str.contains("Monitoring Reality") ||
                    str == "Sinal de Wi-Fi completo" || // Optional: remove tedious status bar text
                    str.contains("barras") // removes signal bar text like "3 barras"
        }

        if (!isNoise(node.text)) {
            sb.append(node.text).append(" ")
        }

        if (!isNoise(node.contentDescription)) {
            sb.append(node.contentDescription).append(" ")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            sb.append(extractText(child))
            child?.recycle()
        }

        return sb.toString().trim()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        rotateLogFile() // Seal on exit
    }

    companion object {
        const val TAG = "TheMirrorEye"
    }
}