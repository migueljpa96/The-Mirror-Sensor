package com.mirror.sensor.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationSensor : NotificationListenerService() {

    private var tempLogFile: File? = null

    // --- DUPLICATE PREVENTION ---
    private var lastNotificationKey: String = ""
    private var lastNotificationTime: Long = 0

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

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "🔔 Listener Connected")
        tempLogFile = File(getExternalFilesDir(null), "temp_notifications.jsonl")

        // --- CRITICAL FIX: Safe Registration for Android 14+ ---
        val filter = IntentFilter("com.mirror.sensor.ROTATE_COMMAND")
        ContextCompat.registerReceiver(
            this,
            rotationReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun rotateLogFile(masterTimestamp: String) {
        if (tempLogFile == null || !tempLogFile!!.exists() || tempLogFile!!.length() == 0L) return

        val finalName = "NOTIFS_$masterTimestamp.jsonl"
        val finalFile = File(getExternalFilesDir(null), finalName)

        if (tempLogFile!!.renameTo(finalFile)) {
            Log.d(TAG, "✅ Notifs Synced: $finalName")
            tempLogFile = File(getExternalFilesDir(null), "temp_notifications.jsonl")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // Filter Noise
        if (sbn.packageName == "com.android.systemui") return
        if (text.contains("new messages", true) || text.contains("mensagens novas", true)) return

        // --- LOGIC FIX: Debounce Duplicates ---
        // Android often sends the same notification event multiple times (creation + hydration).
        // We ignore if it's the exact same content within 2 seconds.
        val currentKey = "${sbn.packageName}|$title|$text"
        val now = System.currentTimeMillis()

        if (currentKey == lastNotificationKey && (now - lastNotificationTime) < 2000) {
            Log.v(TAG, "🚫 Duplicate Ignored: ${sbn.packageName}") // Verbose log
            return // Skip duplicate
        }

        lastNotificationKey = currentKey
        lastNotificationTime = now

        Log.d(TAG, "📨 Capturing: ${sbn.packageName}") // Debug log
        // --------------------------------------

        val entry = JSONObject()
        entry.put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        entry.put("event", "POSTED")
        entry.put("package", sbn.packageName)
        entry.put("title", title)
        entry.put("content", text)

        writeLog(entry)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { }

    private fun writeLog(json: JSONObject) {
        if (tempLogFile == null) return
        try {
            FileOutputStream(tempLogFile, true).use { it.write((json.toString() + "\n").toByteArray()) }
        } catch (e: Exception) { Log.e(TAG, "Write error", e) }
    }

    override fun onDestroy() {
        try { unregisterReceiver(rotationReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object { const val TAG = "TheMirrorNotifs" }
}