package com.mirror.sensor.managers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.work.*
import com.mirror.sensor.data.UploadConfig
import com.mirror.sensor.data.db.AppDatabase
import com.mirror.sensor.data.db.SessionStateEntity
import com.mirror.sensor.data.db.UploadQueueEntity
import com.mirror.sensor.services.AudioService
import com.mirror.sensor.services.PhysicalService
import com.mirror.sensor.services.ScreenService
import com.mirror.sensor.workers.ContextUploadWorker
import com.mirror.sensor.workers.HeavyUploadWorker
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SessionManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var tickerJob: Job? = null

    // Thresholds
    private val MIN_SESSION_DURATION_MS = 5000L // Reduced to 5s for easier testing
    private val MIN_FILE_SIZE_BYTES = 10L

    // State
    private var currentUserId: String? = null
    private var currentSessionId: String? = null
    private var currentSeqIndex: Int = 0
    private var sessionStartTime: Long = 0L

    // Service Binding Synchronization
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
                // Wait for services before resuming
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
                Log.w(TAG, "Waiting for services to bind...")
                withTimeoutOrNull(2000) { boundSignal.await() } ?: run {
                    Log.e(TAG, "❌ Service binding timed out. Cannot start.")
                    return@launch
                }
            }

            if (currentSessionId != null) return@launch

            currentUserId = userId
            val now = System.currentTimeMillis()

            // FIX: Readable Date-Time in Session ID
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
        ScreenService.get()?.startLogging(File(context.filesDir, "SCREEN_${sessId}_$seq.jsonl"))
        audioService?.startRecording(File(context.filesDir, "AUDIO_${sessId}_$seq.m4a"))
    }

    fun stopSession() {
        scope.launch {
            val sessionId = currentSessionId ?: return@launch

            physicalService?.stopLogging()
            ScreenService.get()?.stopLogging()
            audioService?.stopRecording()
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

    private fun discardCurrentFiles(sessionId: String, seq: Int) {
        listOf(
            File(context.filesDir, "PHYS_${sessionId}_$seq.jsonl"),
            File(context.filesDir, "SCREEN_${sessionId}_$seq.jsonl"),
            File(context.filesDir, "AUDIO_${sessionId}_$seq.m4a")
        ).forEach { if (it.exists()) it.delete() }
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
                    ScreenService.get()?.rotateLog(File(context.filesDir, "SCREEN_${sessId}_$nextSeq.jsonl"))
                    audioService?.rotateRecording(File(context.filesDir, "AUDIO_${sessId}_$nextSeq.m4a"))

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

            // Check for empty files
            if (file.length() < MIN_FILE_SIZE_BYTES) {
                Log.w(TAG, "⚠️ Discarding empty: $filename")
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

            WorkManager.getInstance(context).enqueueUniqueWork(
                "context_worker_$userId", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ContextUploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(UploadConfig.TAG_CONTEXT).setInputData(inputData).build()
            )

            WorkManager.getInstance(context).enqueueUniqueWork(
                "heavy_worker_$userId", ExistingWorkPolicy.APPEND_OR_REPLACE,
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