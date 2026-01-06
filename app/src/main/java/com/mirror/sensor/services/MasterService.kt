package com.mirror.sensor.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mirror.sensor.workers.DataUploadWorker
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Assuming you renamed PhysicalCollector -> PhysicalService based on your snippet
// If not, just change this import back to your Collector class
import com.mirror.sensor.services.PhysicalService

class MasterService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var physicalManager: PhysicalService

    // The Master Clock
    private val handler = Handler(Looper.getMainLooper())
    private var tempAudioFile: File? = null
    private val RECORDING_INTERVAL_MS = 10 * 60 * 1000L // 10 Minutes
    private val TEMP_FILENAME = "temp_audio.m4a"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 Service Created")

        // 1. Initialize Helpers
        physicalManager = PhysicalService(this)

        // 2. Acquire WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheMirror:HolisticLock")
        wakeLock?.acquire(RECORDING_INTERVAL_MS * 2)

        // 3. Start Notification
        startForegroundServiceNotification()

        // 4. Begin the Cycle
        startRecordingSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startRecordingSession() {
        try {
            // A. Start Physical Sensors
            physicalManager.startTracking()

            // B. Prepare Temp Audio File
            tempAudioFile = File(getExternalFilesDir(null), TEMP_FILENAME)

            if (tempAudioFile?.exists() == true) {
                tempAudioFile?.delete()
            }

            // C. Configure Recorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(16000)
                setOutputFile(tempAudioFile?.absolutePath)
                prepare()
                start()
            }

            Log.d(TAG, "🎙️ Recording Started: $TEMP_FILENAME")

            // D. Schedule Rotation
            handler.postDelayed({ rotateSession() }, RECORDING_INTERVAL_MS)

        } catch (e: IOException) {
            Log.e(TAG, "❌ Recorder Init Failed", e)
            stopSelf()
        }
    }

    private fun rotateSession() {
        // 1. Generate Timestamp
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        Log.i(TAG, "🔄 ROTATING SESSION: $timestamp")

        // 2. Stop Recording
        stopAndReleaseRecorder()

        // 3. Rename Audio
        if (tempAudioFile?.exists() == true) {
            val finalName = "AUDIO_$timestamp.m4a"
            val finalFile = File(getExternalFilesDir(null), finalName)

            if (tempAudioFile!!.renameTo(finalFile)) {
                Log.d(TAG, "✅ Audio Sealed: $finalName")
            } else {
                Log.e(TAG, "❌ Failed to rename audio file!")
            }
        }

        // 4. Rotate Physical Logs
        physicalManager.rotateLogFile(timestamp)

        // 5. Rotate Accessibility & Notification Logs (BROADCAST)
        val intent = Intent("com.mirror.sensor.ROTATE_COMMAND")
        intent.setPackage(packageName)
        intent.putExtra("TIMESTAMP_ID", timestamp)
        sendBroadcast(intent)
        Log.d(TAG, "📡 Sent Broadcast Rotation: $timestamp")

        // 6. Trigger Upload Worker
        val uploadRequest = OneTimeWorkRequestBuilder<DataUploadWorker>().build()
        WorkManager.getInstance(this).enqueue(uploadRequest)

        // 7. Start New Session
        startRecordingSession()
    }

    private fun stopAndReleaseRecorder() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recorder stop warning: ${e.message}")
        } finally {
            mediaRecorder = null
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "mirror_sensor_channel"
        val channelName = "Holistic Sensor"

        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("The Mirror")
            .setContentText("Syncing Reality...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        Log.w(TAG, "🛑 SERVICE DESTROYED - INITIATING NUCLEAR CLEANUP")

        // 1. Stop Active Components
        stopAndReleaseRecorder()
        physicalManager.stopTracking()
        handler.removeCallbacksAndMessages(null)

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // 2. DELETE ALL GENERATED FILES (Temp + Finalized)
        // We scan the directory and wipe anything that looks like our data.
        val filesDir = getExternalFilesDir(null)
        val files = filesDir?.listFiles()

        var deletedCount = 0
        files?.forEach { file ->
            val name = file.name

            // Match: temp_*, AUDIO_*, PHYSICAL_*, SCREEN_*, NOTIFS_*
            val isMirrorFile = name.startsWith("temp_") ||
                    name.startsWith("AUDIO_") ||
                    name.startsWith("PHYSICAL_") ||
                    name.startsWith("SCREEN_") ||
                    name.startsWith("NOTIFS_")

            // Only delete logs/audio (safeguard against deleting other app data if any)
            val isLogType = name.endsWith(".m4a") || name.endsWith(".jsonl")

            if (isMirrorFile && isLogType) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }

        Log.d(TAG, "🧹 Cleanup Complete: Deleted $deletedCount files from storage.")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        const val TAG = "TheMirrorService"
    }
}