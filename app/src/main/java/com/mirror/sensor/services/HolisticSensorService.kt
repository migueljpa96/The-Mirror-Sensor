package com.mirror.sensor.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mirror.sensor.managers.PhysicalSensorManager
import com.mirror.sensor.R
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HolisticSensorService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var tempAudioFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var physicalManager: PhysicalSensorManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var rotationJob: Job? = null

    // CONFIG: 10 Minute Chunks
    private val CHUNK_DURATION_MS = 10 * 60 * 1000L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 Service Created")
        createNotificationChannel()
        physicalManager = PhysicalSensorManager(this)

        // ACQUIRE WAKELOCK
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mirror:MasterClock")
        wakeLock?.acquire()
        Log.i(TAG, "🔒 WakeLock Acquired (CPU kept awake)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForegroundSafely(notification)
        physicalManager.startTracking()
        startMasterLoop()
        return START_STICKY
    }

    private fun startMasterLoop() {
        if (rotationJob?.isActive == true) return

        rotationJob = serviceScope.launch {
            Log.i(TAG, "⏳ Master Clock Started (${CHUNK_DURATION_MS / 60000} min intervals)")
            startAudioRecording()

            while (isActive) {
                delay(CHUNK_DURATION_MS)

                // The Master Timestamp
                val now = Date()
                val masterTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)
                Log.i(TAG, "🔄 MASTER ROTATION TRIGGERED: ID $masterTimestamp")

                rotateAudioFile(masterTimestamp)
                startAudioRecording()

                physicalManager.rotateLogFile(masterTimestamp)
                sendRotationBroadcast(masterTimestamp)
            }
        }
    }

    private fun sendRotationBroadcast(timestamp: String) {
        val intent = Intent("com.mirror.sensor.ROTATE_COMMAND")
        intent.putExtra("TIMESTAMP_ID", timestamp)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Log.d(TAG, "📢 Sent Broadcast: ROTATE_COMMAND -> $timestamp")
    }

    private fun startAudioRecording() {
        tempAudioFile = File(getExternalFilesDir(null), "temp_audio.raw")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

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
                Log.d(TAG, "🎙️ Mic Active")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Recorder Error", e)
                isRecording = false
            }
        }
    }

    private fun rotateAudioFile(timestampId: String) {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) { Log.w(TAG, "Stop Warning: ${e.message}") }

        mediaRecorder = null
        isRecording = false

        if (tempAudioFile?.exists() == true && tempAudioFile!!.length() > 0) {
            val finalName = "AUDIO_$timestampId.m4a"
            val finalFile = File(getExternalFilesDir(null), finalName)
            if (tempAudioFile!!.renameTo(finalFile)) {
                Log.i(TAG, "💾 Audio Saved: $finalName (${finalFile.length() / 1024} KB)")
            }
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Foreground Start Failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        rotationJob?.cancel()
        physicalManager.stopTracking()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "🔓 WakeLock Released")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sensor Channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The Mirror")
            .setContentText("Syncing Reality...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "HolisticService"
        private const val CHANNEL_ID = "SensorChannel"
        private const val NOTIFICATION_ID = 1
    }
}