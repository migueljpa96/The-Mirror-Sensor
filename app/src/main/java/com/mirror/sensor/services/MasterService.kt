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
import com.mirror.sensor.managers.RealTimeSensorManager
import com.mirror.sensor.workers.DataUploadWorker
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MasterService : Service() {

    // Sub-Services (Helpers)
    private lateinit var physicalService: PhysicalService
    private lateinit var screenService: ScreenService

    // Audio Components
    private var mediaRecorder: MediaRecorder? = null
    private var tempAudioFile: File? = null

    // System Components
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audioPollHandler = Handler(Looper.getMainLooper())

    private val RECORDING_INTERVAL_MS = 10 * 60 * 1000L
    private val TEMP_AUDIO_FILENAME = "temp_audio.m4a"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 MasterService Created")

        // Initialize Helpers
        physicalService = PhysicalService(this)
        screenService = ScreenService(this)

        // WakeLock to keep CPU running
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheMirror:MasterLock")
        wakeLock?.acquire(RECORDING_INTERVAL_MS * 2)

        startForegroundServiceNotification()
        startSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startSession() {
        // 1. Start Helpers
        physicalService.startTracking()
        screenService.startTracking()

        // 2. Start Audio
        startAudioRecording()

        // 3. Schedule Rotation
        handler.postDelayed({ rotateSession() }, RECORDING_INTERVAL_MS)
    }

    private fun startAudioRecording() {
        try {
            tempAudioFile = File(getExternalFilesDir(null), TEMP_AUDIO_FILENAME)
            if (tempAudioFile?.exists() == true) tempAudioFile?.delete()

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

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

            // Start visualizer polling
            audioPollHandler.post(audioPollRunnable)
            Log.d(TAG, "🎙️ Audio Recording Active")

        } catch (e: IOException) {
            Log.e(TAG, "❌ Audio Init Failed", e)
            stopSelf()
        }
    }

    private val audioPollRunnable = object : Runnable {
        override fun run() {
            mediaRecorder?.let {
                try {
                    RealTimeSensorManager.updateAmplitude(it.maxAmplitude)
                } catch (e: Exception) {}
            }
            audioPollHandler.postDelayed(this, 50)
        }
    }

    private fun rotateSession() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        Log.i(TAG, "🔄 ROTATING SESSION: $timestamp")

        // 1. Seal Audio
        stopAndReleaseRecorder()
        if (tempAudioFile?.exists() == true) {
            val finalFile = File(getExternalFilesDir(null), "AUDIO_$timestamp.m4a")
            tempAudioFile!!.renameTo(finalFile)
        }

        // 2. Command Helpers to Seal their logs
        physicalService.rotateLogFile(timestamp)
        screenService.rotateLogFile(timestamp)

        // 3. Notify System
        val intent = Intent("com.mirror.sensor.ROTATE_COMMAND")
        intent.setPackage(packageName)
        intent.putExtra("TIMESTAMP_ID", timestamp)
        sendBroadcast(intent)

        // 4. Trigger Upload
        val uploadRequest = OneTimeWorkRequestBuilder<DataUploadWorker>().build()
        WorkManager.getInstance(this).enqueue(uploadRequest)

        // 5. Restart
        startAudioRecording()
        handler.postDelayed({ rotateSession() }, RECORDING_INTERVAL_MS)
    }

    private fun stopAndReleaseRecorder() {
        audioPollHandler.removeCallbacks(audioPollRunnable)
        RealTimeSensorManager.updateAmplitude(0)
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
    }

    private fun startForegroundServiceNotification() {
        val channelId = "mirror_sensor_channel"
        val channelName = "Holistic Sensor"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("The Mirror")
            .setContentText("Observing Reality...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
    }

    // --- CLEANUP (The Fix) ---
    override fun onDestroy() {
        Log.w(TAG, "🛑 MasterService Destroying...")

        // 1. Stop components immediately
        handler.removeCallbacksAndMessages(null)
        stopAndReleaseRecorder()

        // 2. Ask Helpers to cleanup their own temp files
        if (::physicalService.isInitialized) physicalService.cleanup()
        if (::screenService.isInitialized) screenService.cleanup()

        // 3. Clean up Audio Temp File
        if (tempAudioFile?.exists() == true) {
            val deleted = tempAudioFile?.delete() ?: false
            Log.d(TAG, "🧹 Temp Audio File Deleted: $deleted")
        }

        // 4. Final Safety Sweep (In case anything was left behind)
        val filesDir = getExternalFilesDir(null)
        val files = filesDir?.listFiles()
        files?.forEach { file ->
            if (file.name.startsWith("temp_")) {
                file.delete()
                Log.d(TAG, "🧹 Safety Sweep: Deleted ${file.name}")
            }
        }

        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "TheMirrorService"
        fun startService(context: Context) {
            val intent = Intent(context, MasterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun stopService(context: Context) {
            context.stopService(Intent(context, MasterService::class.java))
        }
    }
}