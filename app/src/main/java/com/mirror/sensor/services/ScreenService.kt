package com.mirror.sensor.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.lang.ref.WeakReference

class ScreenService : AccessibilityService() {

    private val writingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<String>(capacity = 1000)

    private var currentWriter: BufferedWriter? = null
    private var isLogging = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "ScreenService Connected")
        instance = WeakReference(this)

        // Start consumer
        writingScope.launch {
            for (line in logChannel) {
                try {
                    currentWriter?.write(line)
                    currentWriter?.newLine()
                } catch (e: IOException) {
                    Log.e(TAG, "Write failed", e)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isLogging || event == null) return

        // Privacy: We only capture package names and event types for context, NOT text content
        // unless explicitly intended. For "Mirror", we typically want broad context.
        // Format: {"ts":123,"pkg":"com.app","type":1}

        val pkg = event.packageName?.toString() ?: "unknown"
        val type = event.eventType

        // Simple JSON serialization
        val line = "{\"ts\":${System.currentTimeMillis()},\"pkg\":\"$pkg\",\"type\":$type}"
        logChannel.trySend(line)
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenService Interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        writingScope.cancel()
        closeCurrentFile()
        return super.onUnbind(intent)
    }

    // --- Control Methods ---

    fun startLogging(file: File) {
        Log.i(TAG, "Starting logging to: ${file.name}")
        closeCurrentFile()
        try {
            currentWriter = BufferedWriter(FileWriter(file, true))
            isLogging = true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open file", e)
        }
    }

    fun rotateLog(newFile: File) {
        Log.i(TAG, "Rotating log to: ${newFile.name}")
        closeCurrentFile()
        try {
            currentWriter = BufferedWriter(FileWriter(newFile, true))
            isLogging = true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to rotate file", e)
        }
    }

    fun stopLogging() {
        isLogging = false
        closeCurrentFile()
    }

    private fun closeCurrentFile() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (e: IOException) {
            // Ignore close errors
        }
        currentWriter = null
    }

    companion object {
        const val TAG = "MirrorScreenService"

        // Singleton access for SessionManager
        var instance: WeakReference<ScreenService>? = null

        fun get(): ScreenService? = instance?.get()
    }
}