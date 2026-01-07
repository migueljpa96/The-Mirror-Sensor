package com.mirror.sensor.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MasterService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var physicalManager: PhysicalService

    // Usage Stats (Replaces ScreenService)
    private var lastAppPackage: String = ""
    private var tempUsageFile: File? = null

    // Timers
    private val handler = Handler(Looper.getMainLooper())
    private val audioPollHandler = Handler(Looper.getMainLooper())
    private val usagePollHandler = Handler(Looper.getMainLooper())

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

    // POLL APP USAGE every 2 seconds
    private val usagePollRunnable = object : Runnable {
        override fun run() {
            checkCurrentApp()
            usagePollHandler.postDelayed(this, 2000)
        }
    }

    private var tempAudioFile: File? = null
    private val RECORDING_INTERVAL_MS = 10 * 60 * 1000L
    private val TEMP_FILENAME = "temp_audio.m4a"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🟢 Service Created")
        physicalManager = PhysicalService(this)
        tempUsageFile = File(getExternalFilesDir(null), "temp_usage.jsonl")

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

            // Start Pollers
            audioPollHandler.post(audioPollRunnable)
            usagePollHandler.post(usagePollRunnable) // <--- USAGE POLLER

            Log.d(TAG, "🎙️ Recording Started")
            handler.postDelayed({ rotateSession() }, RECORDING_INTERVAL_MS)

        } catch (e: IOException) {
            Log.e(TAG, "❌ Recorder Init Failed", e)
            stopSelf()
        }
    }

    private fun checkCurrentApp() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
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
            FileOutputStream(tempUsageFile, true).use { it.write((entry.toString() + "\n").toByteArray()) }
        } catch (e: Exception) {}
    }

    private fun rotateSession() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        Log.i(TAG, "🔄 ROTATING SESSION: $timestamp")
        stopAndReleaseRecorder()

        // Rotate Audio
        if (tempAudioFile?.exists() == true) {
            val finalFile = File(getExternalFilesDir(null), "AUDIO_$timestamp.m4a")
            tempAudioFile!!.renameTo(finalFile)
        }

        // Rotate Screen
        if (tempUsageFile?.exists() == true && tempUsageFile!!.length() > 0) {
            val finalFile = File(getExternalFilesDir(null), "USAGE_$timestamp.jsonl")
            tempUsageFile!!.renameTo(finalFile)
            tempUsageFile = File(getExternalFilesDir(null), "temp_screen.jsonl") // Reset
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
        audioPollHandler.removeCallbacks(audioPollRunnable)
        usagePollHandler.removeCallbacks(usagePollRunnable)
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
        stopAndReleaseRecorder()
        physicalManager.stopTracking()
        handler.removeCallbacksAndMessages(null)
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