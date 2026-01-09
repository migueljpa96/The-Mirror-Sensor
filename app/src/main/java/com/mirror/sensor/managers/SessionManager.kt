package com.mirror.sensor.managers

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
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
import kotlin.math.roundToInt

// Helper Data Class for cached app info
data class AppInfo(val label: String, val isSystem: Boolean, val category: String?)

class SessionManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Standard Variables
    private var tickerJob: Job? = null
    private var screenPollerJob: Job? = null
    private val MIN_SESSION_DURATION_MS = 5000L
    private val MIN_FILE_SIZE_BYTES = 10L
    private val SCREEN_POLL_INTERVAL_MS = 2000L
    private var currentUserId: String? = null
    private var currentSessionId: String? = null
    private var currentSeqIndex: Int = 0
    private var sessionStartTime: Long = 0L
    private var lastPackageName: String = ""
    private var screenWriter: BufferedWriter? = null
    private val appInfoCache = ConcurrentHashMap<String, AppInfo>()
    private var physicalService: PhysicalService? = null
    private var audioService: AudioService? = null
    private val boundSignal = CompletableDeferred<Unit>()

    // Service Connections
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
    private fun checkBindings() { if (physicalService != null && audioService != null && !boundSignal.isCompleted) boundSignal.complete(Unit) }

    init {
        context.bindService(Intent(context, PhysicalService::class.java), physConnection, Context.BIND_AUTO_CREATE)
        context.bindService(Intent(context, AudioService::class.java), audioConnection, Context.BIND_AUTO_CREATE)
        scope.launch {
            val dao = db.uploadDao()
            dao.resetStuckUploads()
            val savedState = dao.getActiveSession()
            if (savedState != null && savedState.is_active) {
                boundSignal.await()
                currentUserId = savedState.user_id; currentSessionId = savedState.session_id
                currentSeqIndex = savedState.last_seq_index + 1; sessionStartTime = savedState.start_ts
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
            if (!boundSignal.isCompleted) withTimeoutOrNull(2000) { boundSignal.await() }
            if (currentSessionId != null) return@launch
            currentUserId = userId; val now = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val newId = "sess_${sdf.format(Date(now))}_${UUID.randomUUID().toString().substring(0, 4)}"
            currentSessionId = newId; currentSeqIndex = 0; sessionStartTime = now

            // FIX 1: Use Named Arguments for Entity
            db.uploadDao().setSessionState(SessionStateEntity(
                user_id = userId,
                session_id = newId,
                start_ts = now,
                last_seq_index = 0,
                is_active = true
            ))

            log("SESSION_STARTED", newId, 0, mapOf("uid" to userId))
            startLoggingForCurrentShard(); startTicker()
        }
    }

    fun stopSession() {
        scope.launch {
            val sessionId = currentSessionId ?: return@launch
            physicalService?.stopLogging(); audioService?.stopRecording()
            screenPollerJob?.cancel(); closeScreenWriter(); tickerJob?.cancel()
            sealShard(currentUserId!!, sessionId, currentSeqIndex, isFinal = true)
            db.uploadDao().clearSessionState()
            currentSessionId = null; currentUserId = null
        }
    }

    private fun startLoggingForCurrentShard() {
        val sessId = currentSessionId ?: return
        physicalService?.startLogging(File(context.filesDir, "PHYS_${sessId}_$currentSeqIndex.jsonl"))
        audioService?.startRecording(File(context.filesDir, "AUDIO_${sessId}_$currentSeqIndex.m4a"))
        startScreenLogging(File(context.filesDir, "SCREEN_${sessId}_$currentSeqIndex.jsonl"))
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = now - sessionStartTime
                val delayMs = UploadConfig.SHARD_DURATION_MS - (elapsed % UploadConfig.SHARD_DURATION_MS)
                delay(delayMs)

                val sessId = currentSessionId; val uid = currentUserId
                if (sessId != null && uid != null) {
                    val nextSeq = currentSeqIndex + 1
                    physicalService?.rotateLog(File(context.filesDir, "PHYS_${sessId}_$nextSeq.jsonl"))
                    audioService?.rotateRecording(File(context.filesDir, "AUDIO_${sessId}_$nextSeq.m4a"))
                    startScreenLogging(File(context.filesDir, "SCREEN_${sessId}_$nextSeq.jsonl"))
                    sealShard(uid, sessId, currentSeqIndex, isFinal = false)
                    currentSeqIndex = nextSeq

                    // FIX 2: Use Named Arguments here as well
                    db.uploadDao().setSessionState(SessionStateEntity(
                        user_id = uid,
                        session_id = sessId,
                        start_ts = sessionStartTime,
                        last_seq_index = currentSeqIndex,
                        is_active = true
                    ))
                }
            }
        }
    }

    private fun startScreenLogging(file: File) {
        closeScreenWriter()
        try { file.parentFile?.mkdirs(); screenWriter = BufferedWriter(FileWriter(file, true)) } catch (e: Exception) {}
        screenPollerJob?.cancel()
        screenPollerJob = scope.launch {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val pm = context.packageManager
            val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            while (isActive) {
                val now = System.currentTimeMillis()
                val events = usageStatsManager.queryEvents(now - SCREEN_POLL_INTERVAL_MS * 2, now)
                val event = UsageEvents.Event()
                var latestPkg = lastPackageName; var latestClass = ""
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) { latestPkg = event.packageName; latestClass = event.className }
                }
                if (latestPkg != lastPackageName && latestPkg.isNotEmpty()) {
                    lastPackageName = latestPkg
                    val appInfo = resolveAppInfo(pm, latestPkg)
                    val json = JSONObject()
                    json.put("timestamp", logDateFormat.format(Date(now))); json.put("event_type", "APP_SWITCH")
                    json.put("app_name", appInfo.label); json.put("package_name", latestPkg)
                    json.put("activity_context", latestClass); json.put("is_system_app", appInfo.isSystem)
                    if (appInfo.category != null) json.put("category", appInfo.category)
                    try { screenWriter?.write(json.toString()); screenWriter?.newLine(); screenWriter?.flush() } catch (e: Exception) {}
                }
                delay(SCREEN_POLL_INTERVAL_MS)
            }
        }
    }

    private fun closeScreenWriter() { try { screenWriter?.flush(); screenWriter?.close() } catch (e: Exception) {}; screenWriter = null }
    private fun resolveAppInfo(pm: PackageManager, pkg: String): AppInfo { return appInfoCache.getOrPut(pkg) { AppInfo(pkg, false, null) } }

    private fun summarizePhysicalLog(file: File): String {
        if (!file.exists()) return "Physical Context: No data."
        try {
            val lines = file.readLines(); if (lines.isEmpty()) return "Physical Context: No events."
            val postures = mutableMapOf<String, Long>(); val motions = mutableMapOf<String, Long>()
            var totalDuration = 0L; var network = "Unknown"; var startLoc: Pair<Double, Double>? = null; var endLoc: Pair<Double, Double>? = null

            lines.forEach { line ->
                val json = JSONObject(line)
                if (json.has("prev_posture")) {
                    val p = json.getString("prev_posture"); val d = json.optLong("prev_duration_sec", 0)
                    postures[p] = postures.getOrDefault(p, 0L) + d; totalDuration += d
                }
                if (json.has("curr_motion")) { val m = json.getString("curr_motion"); motions[m] = motions.getOrDefault(m, 0L) + 1 }
                if (json.has("curr_network")) network = json.getString("curr_network")
                if (json.has("lat")) { val loc = Pair(json.getDouble("lat"), json.getDouble("lng")); if (startLoc == null) startLoc = loc; endLoc = loc }
            }

            val sb = StringBuilder()
            val domMotion = motions.maxByOrNull { it.value }?.key ?: "STATIONARY"
            sb.append("Activity: $domMotion. ")

            if (totalDuration > 0) {
                val postStr = postures.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key} (${(it.value * 100 / totalDuration)}%)" }
                sb.append("Posture: $postStr. ")
            }
            sb.append("Network: $network. ")

            if (startLoc != null && endLoc != null) {
                val res = FloatArray(1)
                Location.distanceBetween(startLoc!!.first, startLoc!!.second, endLoc!!.first, endLoc!!.second, res)
                sb.append(if (res[0] > 50) "Location: Moved ~${res[0].toInt()}m." else "Location: Stationary.")
            }
            return sb.toString()
        } catch (e: Exception) { return "Physical Context: Parse Error." }
    }

    private fun summarizeScreenLog(file: File): String {
        if (!file.exists()) return "Screen Context: No data."
        try {
            val lines = file.readLines(); if (lines.isEmpty()) return "Screen Context: No events."
            val appCounts = mutableMapOf<String, Int>(); val categories = mutableMapOf<String, Int>()
            var tsFirst: Long = 0; var tsLast: Long = 0
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

            lines.forEach { line ->
                val json = JSONObject(line)
                val t = fmt.parse(json.getString("timestamp"))?.time ?: 0L
                if (tsFirst == 0L) tsFirst = t
                tsLast = t

                val app = json.optString("app_name", "Unknown")
                val cat = json.optString("category", "Unknown")
                if (app != "TheMirrorSensor" && !app.contains("Launcher")) {
                    appCounts[app] = appCounts.getOrDefault(app, 0) + 1
                    if (cat != "Unknown") categories[cat] = categories.getOrDefault(cat, 0) + 1
                }
            }

            val sb = StringBuilder()
            val durationMin = (tsLast - tsFirst) / 60000.0
            val totalSwitches = appCounts.values.sum()
            val switchesPerMin = if (durationMin > 0) (totalSwitches / durationMin).roundToInt() else 0

            sb.append("Focus: ${if (switchesPerMin > 5) "Fragmented" else "Focused Flow"} ($switchesPerMin switches/min). ")

            if (appCounts.isNotEmpty()) {
                val topApps = appCounts.entries.sortedByDescending { it.value }.take(3)
                    .joinToString(", ") { "${it.key} (${it.value})" }
                sb.append("Dominant Apps: $topApps. ")
            }
            if (categories.isNotEmpty()) {
                val topCats = categories.entries.sortedByDescending { it.value }.take(2)
                    .joinToString(", ") { "${it.key} (${it.value})" }
                sb.append("Categories: $topCats.")
            }
            return sb.toString()
        } catch (e: Exception) { return "Screen Context: Parse Error." }
    }

    private suspend fun sealShard(userId: String, sessionId: String, seq: Int, isFinal: Boolean) {
        val traceId = UUID.randomUUID().toString()
        val dao = db.uploadDao()

        val physFile = File(context.filesDir, "PHYS_${sessionId}_$seq.jsonl")
        val screenFile = File(context.filesDir, "SCREEN_${sessionId}_$seq.jsonl")

        val summaryText = StringBuilder()
        summaryText.append("--- PHYSICAL CONTEXT ---\n").append(summarizePhysicalLog(physFile)).append("\n\n")
        summaryText.append("--- SCREEN CONTEXT ---\n").append(summarizeScreenLog(screenFile))

        val summaryFile = File(context.filesDir, "SUMMARY_${sessionId}_$seq.txt")
        summaryFile.writeText(summaryText.toString())

        Log.i("MirrorBrain", "🧠 GENERATED SUMMARY [$seq]:\n$summaryText")

        val filesToQueue = listOf(
            Triple(summaryFile, "CONTEXT_SUMMARY", true),
            Triple(physFile, "PHYS_LOG", physFile.length() > MIN_FILE_SIZE_BYTES),
            Triple(screenFile, "SCREEN_LOG", screenFile.length() > MIN_FILE_SIZE_BYTES),
            Triple(File(context.filesDir, "AUDIO_${sessionId}_$seq.m4a"), "AUDIO", File(context.filesDir, "AUDIO_${sessionId}_$seq.m4a").length() > MIN_FILE_SIZE_BYTES)
        )

        var hasData = false
        for ((file, type, isValid) in filesToQueue) {
            if (isValid && file.exists()) {
                dao.insertQueueItem(UploadQueueEntity(
                    user_id = userId, session_id = sessionId, seq_index = seq,
                    trace_id = traceId, file_path = file.absolutePath, file_type = type
                ))
                hasData = true
            } else if (file.exists()) { file.delete() }
        }

        if (hasData) {
            val inputData = Data.Builder().putString("USER_ID", userId).build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork("context_$userId", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ContextUploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(UploadConfig.TAG_CONTEXT).setInputData(inputData).build())
            wm.enqueueUniqueWork("heavy_$userId", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<HeavyUploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .addTag(UploadConfig.TAG_HEAVY).setInputData(inputData).build())
        }
    }

    private fun discardCurrentFiles(sessionId: String, seq: Int) {
        listOf("PHYS", "SCREEN", "AUDIO", "SUMMARY").forEach { type ->
            val ext = if (type == "AUDIO") "m4a" else if (type == "SUMMARY") "txt" else "jsonl"
            File(context.filesDir, "${type}_${sessionId}_$seq.$ext").delete()
        }
    }

    private suspend fun recoverOrphans(userId: String, sessionId: String, seq: Int) { sealShard(userId, sessionId, seq, false) }

    private fun log(event: String, sid: String, seq: Int, extras: Map<String, Any>) {
        val json = JSONObject()
        json.put("event", event); json.put("sid", sid); json.put("seq", seq)
        json.put("ts", System.currentTimeMillis())
        extras.forEach { (k, v) -> json.put(k, v) }
        Log.i(TAG, json.toString())
    }

    companion object { const val TAG = "MirrorSession" }
}