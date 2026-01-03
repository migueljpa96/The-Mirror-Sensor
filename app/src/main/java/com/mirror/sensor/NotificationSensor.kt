package com.mirror.sensor

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

class NotificationSensor : NotificationListenerService() {

    private var tempLogFile: File? = null

    // Rotation Logic
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var rotationJob: Job? = null
    private val ROTATION_INTERVAL_MS = 10 * 60 * 1000L

    // Deduplication State
    private var lastPostedKey: String = ""
    private var lastPostedTime: Long = 0
    private var lastRemovedPackage: String = ""
    private var lastRemovedTime: Long = 0

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification Sensor Connected")
        tempLogFile = File(getExternalFilesDir(null), "temp_notifications.jsonl")

        rotationJob = scope.launch {
            while (isActive) {
                delay(ROTATION_INTERVAL_MS)
                rotateLogFile()
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // 1. SYSTEM NOISE FILTER
        if (sbn.packageName == "com.android.systemui" && (text.isEmpty() || title == "Edge lighting")) return

        // 2. WHATSAPP SUMMARY FILTER (Crucial Fix)
        // If the text says "X new messages" (in various languages), it's a summary, not content.
        // We check for the common "mensagens novas" pattern or simple "X messages" format if needed.
        if (text.contains("mensagens novas", ignoreCase = true) ||
            text.contains("new messages", ignoreCase = true)) {
            return
        }

        // 3. POST DEDUPLICATION
        val currentKey = "${sbn.packageName}|$title|$text"
        val now = System.currentTimeMillis()

        if (currentKey == lastPostedKey && (now - lastPostedTime) < 2000) return

        lastPostedKey = currentKey
        lastPostedTime = now

        val entry = JSONObject()
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        entry.put("event", "POSTED")
        entry.put("package", sbn.packageName)
        entry.put("title", title)
        entry.put("content", text)
        entry.put("is_ongoing", sbn.isOngoing)

        writeLog(entry)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val now = System.currentTimeMillis()
        val packageName = sbn.packageName

        // 4. REMOVAL DEDUPLICATION (The Fix for double REMOVED)
        // If we just logged a removal for this package in the last 1 second, ignore this one.
        if (packageName == lastRemovedPackage && (now - lastRemovedTime) < 1000) return

        lastRemovedPackage = packageName
        lastRemovedTime = now

        val entry = JSONObject()
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        entry.put("event", "REMOVED")
        entry.put("package", packageName)

        writeLog(entry)
    }

    private fun writeLog(json: JSONObject) {
        if (tempLogFile == null) return
        try {
            FileOutputStream(tempLogFile, true).use { stream ->
                stream.write((json.toString() + "\n").toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write error", e)
        }
    }

    private fun rotateLogFile() {
        if (tempLogFile == null || !tempLogFile!!.exists() || tempLogFile!!.length() == 0L) return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val finalName = "NOTIFS_$timeStamp.jsonl"
        val finalFile = File(getExternalFilesDir(null), finalName)

        if (tempLogFile!!.renameTo(finalFile)) {
            Log.d(TAG, "Rotated Notification Log: $finalName")
            tempLogFile = File(getExternalFilesDir(null), "temp_notifications.jsonl")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rotationJob?.cancel()
        rotateLogFile()
    }

    companion object {
        const val TAG = "TheMirrorNotifs"
    }
}