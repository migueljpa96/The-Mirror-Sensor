package com.mirror.sensor.services

import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.IOException

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

    // --- Control Methods ---

    fun startRecording(file: File) {
        if (isRecording) {
            Log.w(TAG, "Already recording. Rotating instead.")
            rotateRecording(file)
            return
        }

        Log.i(TAG, "Starting Audio: ${file.name}")
        startRecorderInternal(file)
    }

    fun rotateRecording(newFile: File) {
        Log.i(TAG, "Rotating Audio to: ${newFile.name}")

        // 1. Stop current
        stopRecorderInternal()

        // 2. Start new
        startRecorderInternal(newFile)
    }

    fun stopRecording() {
        Log.i(TAG, "Stopping Audio")
        stopRecorderInternal()
    }

    private fun startRecorderInternal(file: File) {
        try {
            // Ensure directory exists
            file.parentFile?.mkdirs()

            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000) // 64kbps is sufficient for voice
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
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Stop failed (e.g., started but immediate stop, or no data)
            Log.e(TAG, "Error stopping recorder", e)
            // If empty file created, delete it to avoid clutter
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
    }

    companion object {
        const val TAG = "MirrorAudioService"
    }
}