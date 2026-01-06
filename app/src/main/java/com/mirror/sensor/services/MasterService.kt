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
import com.mirror.sensor.managers.RealTimeSensorManager // <--- IMPORT THIS
import com.mirror.sensor.workers.DataUploadWorker
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mirror.sensor.services.PhysicalService

class MasterService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var physicalManager: PhysicalService

    // Timers
    private val handler = Handler(Looper.getMainLooper())

    // --- NEW: Audio Polling ---
    private val audioPollHandler = Handler(Looper.getMainLooper())
    private val audioPollRunnable = object : Runnable {
        override fun run() {
            mediaRecorder?.let {
                try {
                    // Read Mic Volume
                    val amp = it.maxAmplitude
                    RealTimeSensorManager.updateAmplitude(amp)
                } catch (e: Exception) {
                    // Ignore transient errors
                }
            }
            // Repeat every 50ms (20 FPS)
            audioPollHandler.postDelayed(this, 50)
        }
    }

    private var tempAudioFile: File? = null
    private val RECORDING_INTERVAL_MS = 10 * 60 * 1000L
    private val TEMP_FILENAME = "temp_audio.m4a"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 Service Created")
        physicalManager = PhysicalService(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheMirror:HolisticLock")
        wakeLock?.acquire(RECORDING_INTERVAL_MS * 2)

        startForegroundServiceNotification()
        startRecordingSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startRecordingSession() {
        try {
            physicalManager.startTracking()
            tempAudioFile = File(getExternalFilesDir(null), TEMP_FILENAME)
            if (tempAudioFile?.exists() == true) tempAudioFile?.delete()

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

            // --- START POLLING ---
            audioPollHandler.post(audioPollRunnable) // <--- Start the visualizer data

            Log.d(TAG, "🎙️ Recording Started: $TEMP_FILENAME")
            handler.postDelayed({ rotateSession() }, RECORDING_INTERVAL_MS)

        } catch (e: IOException) {
            Log.e(TAG, "❌ Recorder Init Failed", e)
            stopSelf()
        }
    }

    private fun rotateSession() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        Log.i(TAG, "🔄 ROTATING SESSION: $timestamp")

        stopAndReleaseRecorder()

        if (tempAudioFile?.exists() == true) {
            val finalName = "AUDIO_$timestamp.m4a"
            val finalFile = File(getExternalFilesDir(null), finalName)
            if (tempAudioFile!!.renameTo(finalFile)) Log.d(TAG, "✅ Audio Sealed: $finalName")
        }

        physicalManager.rotateLogFile(timestamp)

        val intent = Intent("com.mirror.sensor.ROTATE_COMMAND")
        intent.setPackage(packageName)
        intent.putExtra("TIMESTAMP_ID", timestamp)
        sendBroadcast(intent)

        val uploadRequest = OneTimeWorkRequestBuilder<DataUploadWorker>().build()
        WorkManager.getInstance(this).enqueue(uploadRequest)

        startRecordingSession()
    }

    private fun stopAndReleaseRecorder() {
        // --- STOP POLLING ---
        audioPollHandler.removeCallbacks(audioPollRunnable) // <--- Stop updates
        RealTimeSensorManager.updateAmplitude(0) // Reset UI to 0

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
        Log.w(TAG, "🛑 SERVICE DESTROYED")

        // 1. Stop Active Components
        stopAndReleaseRecorder()
        physicalManager.stopTracking()
        handler.removeCallbacksAndMessages(null)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // 2. SAFE CLEANUP: Only delete TEMP files.
        // Leave sealed "AUDIO_"/"SCREEN_" files for the DataUploadWorker to handle.
        val filesDir = getExternalFilesDir(null)
        val files = filesDir?.listFiles()

        var deletedCount = 0
        files?.forEach { file ->
            val name = file.name

            // ONLY target files starting with "temp_"
            if (name.startsWith("temp_")) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }

        Log.d(TAG, "🧹 Cleanup Complete: Deleted $deletedCount temp files. Preserved finalized data.")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        const val TAG = "TheMirrorService"
    }
}