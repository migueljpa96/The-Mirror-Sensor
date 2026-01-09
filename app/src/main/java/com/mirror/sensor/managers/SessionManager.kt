package com.mirror.sensor.managers

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.work.*
import com.mirror.sensor.data.UploadConfig
import com.mirror.sensor.data.db.AppDatabase
import com.mirror.sensor.data.db.SessionStateEntity
import com.mirror.sensor.data.db.UploadQueueEntity
import com.mirror.sensor.services.AudioService
import com.mirror.sensor.services.PhysicalService
import com.mirror.sensor.workers.ContextUploadWorker
import com.mirror.sensor.workers.HeavyUploadWorker
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Helper Data Class for cached app info
data class AppInfo(
    val label: String,
    val isSystem: Boolean,
    val category: String?
)

class SessionManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var tickerJob: Job? = null
    private var screenPollerJob: Job? = null

    // Thresholds
    private val MIN_SESSION_DURATION_MS = 5000L
    private val MIN_FILE_SIZE_BYTES = 10L
    private val SCREEN_POLL_INTERVAL_MS = 2000L

    // State
    private var currentUserId: String? = null
    private var currentSessionId: String? = null
    private var currentSeqIndex: Int = 0
    private var sessionStartTime: Long = 0L

    // Screen State & Caching
    private var lastPackageName: String = ""
    private var screenWriter: BufferedWriter? = null
    private val appInfoCache = ConcurrentHashMap<String, AppInfo>()

    // Services
    private var physicalService: PhysicalService? = null
    private var audioService: AudioService? = null
    private val boundSignal = CompletableDeferred<Unit>()

    private val physConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            physicalService = (service as PhysicalService.LocalBinder).getService()
            checkBindings()
        }
        override fun onServiceDisconnected(arg0: ComponentName) { physicalService = null }
    }

    private val audioConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            audioService = (service as AudioService.LocalBinder).getService()
            checkBindings()
        }
        override fun onServiceDisconnected(arg0: ComponentName) { audioService = null }
    }

    private fun checkBindings() {
        if (physicalService != null && audioService != null) {
            if (!boundSignal.isCompleted) boundSignal.complete(Unit)
        }
    }

    init {
        context.bindService(Intent(context, PhysicalService::class.java), physConnection, Context.BIND_AUTO_CREATE)
        context.bindService(Intent(context, AudioService::class.java), audioConnection, Context.BIND_AUTO_CREATE)

        scope.launch {
            val dao = db.uploadDao()
            dao.resetStuckUploads()

            val savedState = dao.getActiveSession()
            if (savedState != null && savedState.is_active) {
                boundSignal.await()
                currentUserId = savedState.user_id
                currentSessionId = savedState.session_id
                currentSeqIndex = savedState.last_seq_index + 1
                sessionStartTime = savedState.start_ts
                recoverOrphans(savedState.user_id, savedState.session_id, savedState.last_seq_index)
                log("SESSION_RESUMED", currentSessionId!!, currentSeqIndex, emptyMap())
                startLoggingForCurrentShard()
                startTicker()
            }
        }
    }

    fun startSession(userId: String) {
        if (!UploadConfig.SESSION_CHUNKING_ENABLED) return

        scope.launch {
            if (!boundSignal.isCompleted) {
                withTimeoutOrNull(2000) { boundSignal.await() } ?: run {
                    Log.e(TAG, "❌ Service binding timed out. Cannot start.")
                    return@launch
                }
            }

            if (currentSessionId != null) return@launch

            currentUserId = userId
            val now = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val formattedTime = sdf.format(Date(now))
            val newId = "sess_${formattedTime}_${UUID.randomUUID().toString().substring(0, 4)}"

            currentSessionId = newId
            currentSeqIndex = 0
            sessionStartTime = now

            db.uploadDao().setSessionState(SessionStateEntity(
                user_id = userId, session_id = newId, start_ts = now,
                last_seq_index = 0, is_active = true
            ))

            log("SESSION_STARTED", newId, 0, mapOf("uid" to userId))
            startLoggingForCurrentShard()
            startTicker()
        }
    }

    private fun startLoggingForCurrentShard() {
        val sessId = currentSessionId ?: return
        val seq = currentSeqIndex

        physicalService?.startLogging(File(context.filesDir, "PHYS_${sessId}_$seq.jsonl"))
        audioService?.startRecording(File(context.filesDir, "AUDIO_${sessId}_$seq.m4a"))
        startScreenLogging(File(context.filesDir, "SCREEN_${sessId}_$seq.jsonl"))
    }

    private fun startScreenLogging(file: File) {
        closeScreenWriter()
        try {
            file.parentFile?.mkdirs()
            screenWriter = BufferedWriter(FileWriter(file, true))
        } catch (e: Exception) { Log.e(TAG, "Failed to open screen log", e) }

        screenPollerJob?.cancel()
        screenPollerJob = scope.launch {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val pm = context.packageManager
            val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

            while (isActive) {
                val now = System.currentTimeMillis()
                val events = usageStatsManager.queryEvents(now - SCREEN_POLL_INTERVAL_MS * 2, now)
                val event = UsageEvents.Event()

                var latestPkg = lastPackageName
                var latestClass = ""

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        latestPkg = event.packageName
                        latestClass = event.className
                    }
                }

                if (latestPkg != lastPackageName && latestPkg.isNotEmpty()) {
                    lastPackageName = latestPkg
                    val appInfo = resolveAppInfo(pm, latestPkg)

                    val json = JSONObject()
                    json.put("timestamp", logDateFormat.format(Date(now)))
                    json.put("event_type", "APP_SWITCH")
                    json.put("app_name", appInfo.label) // Now "WhatsApp", not "com.whatsapp"
                    json.put("package_name", latestPkg)
                    json.put("activity_context", latestClass)
                    json.put("is_system_app", appInfo.isSystem)

                    // Only add category if valid
                    if (appInfo.category != null) {
                        json.put("category", appInfo.category)
                    }

                    try {
                        screenWriter?.write(json.toString())
                        screenWriter?.newLine()
                        screenWriter?.flush()
                    } catch (e: IOException) { Log.e(TAG, "Screen write failed", e) }
                }

                delay(SCREEN_POLL_INTERVAL_MS)
            }
        }
    }

    private fun resolveAppInfo(pm: PackageManager, pkg: String): AppInfo {
        return appInfoCache.getOrPut(pkg) {
            try {
                val ai = pm.getApplicationInfo(pkg, 0)

                // A. Better Label Resolution
                var label = pm.getApplicationLabel(ai).toString()

                // If label is just the package name (e.g. "com.whatsapp"), format it manually
                if (label == pkg || label.contains(".")) {
                    val parts = pkg.split(".")
                    if (parts.isNotEmpty()) {
                        // "com.microsoft.teams" -> "Teams"
                        label = parts.last().replaceFirstChar { it.uppercase() }
                    }
                }

                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                // B. Better Category Handling
                var catLabel: String? = null
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val catTitle = ApplicationInfo.getCategoryTitle(context, ai.category)
                    // Only store if it's a real category, not "Unknown"
                    if (catTitle != null && catTitle != "Unknown") {
                        catLabel = catTitle.toString()
                    }
                }

                AppInfo(label, isSystem, catLabel)
            } catch (e: Exception) {
                // Fallback: com.example.myapp -> Myapp
                val simpleName = pkg.split(".").last().replaceFirstChar { it.uppercase() }
                AppInfo(simpleName, false, null)
            }
        }
    }

    fun stopSession() {
        scope.launch {
            val sessionId = currentSessionId ?: return@launch

            physicalService?.stopLogging()
            audioService?.stopRecording()

            screenPollerJob?.cancel()
            closeScreenWriter()
            tickerJob?.cancel()

            val duration = System.currentTimeMillis() - sessionStartTime
            if (duration < MIN_SESSION_DURATION_MS) {
                log("SESSION_DISCARDED", sessionId, currentSeqIndex, mapOf("reason" to "too_short"))
                discardCurrentFiles(sessionId, currentSeqIndex)
            } else {
                sealShard(currentUserId!!, sessionId, currentSeqIndex, isFinal = true)
                log("SESSION_STOPPED", sessionId, currentSeqIndex, mapOf("dur" to duration))
            }

            db.uploadDao().clearSessionState()
            currentSessionId = null
            currentUserId = null
        }
    }

    private fun closeScreenWriter() {
        try {
            screenWriter?.flush()
            screenWriter?.close()
        } catch (e: Exception) {}
        screenWriter = null
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = now - sessionStartTime
                val timeInShard = elapsed % UploadConfig.SHARD_DURATION_MS
                val delayMs = UploadConfig.SHARD_DURATION_MS - timeInShard

                delay(delayMs)

                val sessId = currentSessionId
                val uid = currentUserId

                if (sessId != null && uid != null) {
                    val nextSeq = currentSeqIndex + 1

                    physicalService?.rotateLog(File(context.filesDir, "PHYS_${sessId}_$nextSeq.jsonl"))
                    audioService?.rotateRecording(File(context.filesDir, "AUDIO_${sessId}_$nextSeq.m4a"))
                    startScreenLogging(File(context.filesDir, "SCREEN_${sessId}_$nextSeq.jsonl"))

                    sealShard(uid, sessId, currentSeqIndex, isFinal = false)

                    currentSeqIndex = nextSeq
                    val dao = db.uploadDao()
                    val state = dao.getActiveSession()
                    if (state != null && state.session_id == sessId) {
                        dao.setSessionState(state.copy(last_seq_index = currentSeqIndex))
                    }
                }
            }
        }
    }

    private fun discardCurrentFiles(sessionId: String, seq: Int) {
        listOf(
            File(context.filesDir, "PHYS_${sessionId}_$seq.jsonl"),
            File(context.filesDir, "SCREEN_${sessionId}_$seq.jsonl"),
            File(context.filesDir, "AUDIO_${sessionId}_$seq.m4a")
        ).forEach { if (it.exists()) it.delete() }
    }

    private suspend fun recoverOrphans(userId: String, sessionId: String, seq: Int) {
        sealShard(userId, sessionId, seq, isFinal = false)
    }

    private suspend fun sealShard(userId: String, sessionId: String, seq: Int, isFinal: Boolean) {
        val traceId = UUID.randomUUID().toString()
        val dao = db.uploadDao()
        var hasValidData = false

        suspend fun queueIfValid(type: String, filename: String) {
            val file = File(context.filesDir, filename)
            if (!file.exists()) return
            if (file.length() < MIN_FILE_SIZE_BYTES) {
                file.delete()
                return
            }
            hasValidData = true
            dao.insertQueueItem(UploadQueueEntity(
                user_id = userId, session_id = sessionId, seq_index = seq,
                trace_id = traceId, file_path = file.absolutePath, file_type = type
            ))
        }

        queueIfValid("PHYS_LOG", "PHYS_${sessionId}_$seq.jsonl")
        queueIfValid("SCREEN_LOG", "SCREEN_${sessionId}_$seq.jsonl")
        queueIfValid("AUDIO", "AUDIO_${sessionId}_$seq.m4a")

        if (hasValidData) {
            val inputData = Data.Builder().putString("USER_ID", userId).build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork("context_worker_$userId", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ContextUploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(UploadConfig.TAG_CONTEXT).setInputData(inputData).build())
            wm.enqueueUniqueWork("heavy_worker_$userId", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<HeavyUploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .addTag(UploadConfig.TAG_HEAVY).setInputData(inputData).build()
            )
        }
    }

    private fun log(event: String, sid: String, seq: Int, extras: Map<String, Any>) {
        val json = JSONObject()
        json.put("event", event); json.put("sid", sid); json.put("seq", seq)
        json.put("ts", System.currentTimeMillis())
        extras.forEach { (k, v) -> json.put(k, v) }
        Log.i(TAG, json.toString())
    }

    companion object { const val TAG = "MirrorSession" }
}