package com.mirror.sensor.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mirror.sensor.R
import java.io.File

class AudioService : Service() {

    private val binder = LocalBinder()
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var isRecording = false

    inner class LocalBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirror Audio Active")
            .setContentText("Recording audio context...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1002, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Mirror Audio Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // --- Control Methods ---

    fun startRecording(file: File) {
        if (isRecording) {
            rotateRecording(file)
            return
        }

        // Promote to Foreground BEFORE accessing mic
        startForegroundService()

        Log.i(TAG, "🎙️ Starting Audio: ${file.name}")
        startRecorderInternal(file)
    }

    fun rotateRecording(newFile: File) {
        Log.i(TAG, "🔄 Rotating Audio to: ${newFile.name}")
        stopRecorderInternal()
        startRecorderInternal(newFile)
    }

    fun stopRecording() {
        Log.i(TAG, "🛑 Stopping Audio")
        stopRecorderInternal()
        stopForeground(true)
    }

    private fun startRecorderInternal(file: File) {
        try {
            file.parentFile?.mkdirs()
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            currentFile = file
            isRecording = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recorder", e)
            isRecording = false
            recorder = null
        }
    }

    private fun stopRecorderInternal() {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            currentFile?.delete()
        } finally {
            recorder = null
            isRecording = false
            currentFile = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecorderInternal()
        stopForeground(true)
    }

    companion object {
        const val TAG = "MirrorAudioService"
        const val CHANNEL_ID = "mirror_sensor_channel"
    }
}