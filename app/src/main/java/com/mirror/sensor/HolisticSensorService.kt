package com.mirror.sensor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HolisticSensorService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    // Defines the "Active" file that the Worker must NOT touch
    private var tempAudioFile: File? = null

    private lateinit var physicalManager: PhysicalSensorManager

    // Coroutine Scope for the "Chunking" Timer
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var rotationJob: Job? = null

    // CONFIG: How long is one audio chunk? (e.g., 10 minutes)
    private val CHUNK_DURATION_MS = 10 * 60 * 1000L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        physicalManager = PhysicalSensorManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasMic = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                var type = 0
                if (hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        // START THE LOOP
        startRecordingLoop()

        physicalManager.startTracking()

        return START_STICKY
    }

    private fun startRecordingLoop() {
        if (rotationJob?.isActive == true) return

        rotationJob = serviceScope.launch {
            while (isActive) {
                startAudioRecording()
                // Wait for the chunk duration
                delay(CHUNK_DURATION_MS)
                // Stop and Rename the file so the Worker can take it
                rotateFile()
            }
        }
    }

    private fun startAudioRecording() {
        // Always write to "temp_audio.raw" first.
        // We use .raw extension so the DataUploadWorker (looking for .m4a) ignores it.
        tempAudioFile = File(getExternalFilesDir(null), "temp_audio.raw")

        Log.d(TAG, "Starting new chunk: ${tempAudioFile?.absolutePath}")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(tempAudioFile!!.absolutePath)

                prepare()
                start()
                isRecording = true

            } catch (e: Exception) {
                Log.e(TAG, "Recorder failed to start", e)
                isRecording = false
            }
        }
    }

    private fun rotateFile() {
        if (!isRecording) return

        Log.d(TAG, "Rotating Audio Chunk...")

        // 1. Stop Recording
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }
        mediaRecorder = null
        isRecording = false

        // 2. Rename "temp_audio.raw" -> "AUDIO_20260101_120000.m4a"
        if (tempAudioFile != null && tempAudioFile!!.exists()) {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val finalName = "AUDIO_$timeStamp.m4a"
            val finalFile = File(getExternalFilesDir(null), finalName)

            val success = tempAudioFile!!.renameTo(finalFile)
            if (success) {
                Log.d(TAG, "Chunk Sealed: ${finalFile.name}")
            } else {
                Log.e(TAG, "Failed to rename chunk!")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rotationJob?.cancel() // Stop the loop
        rotateFile() // Seal the last file before dying
        physicalManager.stopTracking()
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    // ... createNotificationChannel and createNotification logic remains exactly the same ...
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sensor Service Channel", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The Mirror Sensor")
            .setContentText("Monitoring Reality (Audio + Sensors)...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "HolisticSensorService"
        private const val CHANNEL_ID = "SensorChannel"
        private const val NOTIFICATION_ID = 1
    }
}