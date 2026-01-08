package com.mirror.sensor.managers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mirror.sensor.data.UploadConfig
import com.mirror.sensor.data.db.AppDatabase
import com.mirror.sensor.data.db.SessionStateEntity
import com.mirror.sensor.data.db.UploadQueueEntity
import com.mirror.sensor.services.AudioService
import com.mirror.sensor.services.PhysicalService
import com.mirror.sensor.services.ScreenService
import com.mirror.sensor.workers.ContextUploadWorker
import com.mirror.sensor.workers.HeavyUploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SessionManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var tickerJob: Job? = null

    private var currentUserId: String? = null
    private var currentSessionId: String? = null
    private var currentSeqIndex: Int = 0

    // --- SERVICE BINDINGS ---
    private var physicalService: PhysicalService? = null
    private var audioService: AudioService? = null

    private val physConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            physicalService = (service as PhysicalService.LocalBinder).getService()
            Log.i(TAG, "PhysicalService Bound")
        }
        override fun onServiceDisconnected(arg0: ComponentName) { physicalService = null }
    }

    private val audioConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            audioService = (service as AudioService.LocalBinder).getService()
            Log.i(TAG, "AudioService Bound")
        }
        override fun onServiceDisconnected(arg0: ComponentName) { audioService = null }
    }

    init {
        // Bind to Services
        val physIntent = Intent(context, PhysicalService::class.java)
        context.bindService(physIntent, physConnection, Context.BIND_AUTO_CREATE)

        val audioIntent = Intent(context, AudioService::class.java)
        context.bindService(audioIntent, audioConnection, Context.BIND_AUTO_CREATE)

        scope.launch {
            val dao = db.uploadDao()
            dao.resetStuckUploads()

            // Resume Logic
            val savedState = dao.getActiveSession()
            if (savedState != null && savedState.is_active) {
                currentUserId = savedState.user_id
                currentSessionId = savedState.session_id
                currentSeqIndex = savedState.last_seq_index + 1 // New shard on resume

                log("SESSION_RESUMED", currentSessionId!!, currentSeqIndex, emptyMap())

                // Immediately start logging to the new shard
                startLoggingForCurrentShard()
                startTicker()
            }
        }
    }

    fun startSession(userId: String) {
        if (!UploadConfig.SESSION_CHUNKING_ENABLED) return

        scope.launch {
            if (currentSessionId != null) return@launch

            currentUserId = userId
            val newId = "sess_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 4)}"
            currentSessionId = newId
            currentSeqIndex = 0

            db.uploadDao().setSessionState(SessionStateEntity(
                user_id = userId, session_id = newId, start_ts = System.currentTimeMillis(),
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

        // 1. Physical
        physicalService?.startLogging(File(context.filesDir, "PHYS_${sessId}_$seq.jsonl"))

        // 2. Screen (Singleton)
        ScreenService.get()?.startLogging(File(context.filesDir, "SCREEN_${sessId}_$seq.jsonl"))

        // 3. Audio
        audioService?.startRecording(File(context.filesDir, "AUDIO_${sessId}_$seq.m4a"))
    }

    fun stopSession() {
        scope.launch {
            val sessionId = currentSessionId ?: return@launch

            // Stop All
            physicalService?.stopLogging()
            ScreenService.get()?.stopLogging()
            audioService?.stopRecording()

            // Seal Final
            sealShard(currentUserId!!, sessionId, currentSeqIndex, isFinal = true)

            tickerJob?.cancel()
            db.uploadDao().clearSessionState()

            log("SESSION_STOPPED", sessionId, currentSeqIndex, emptyMap())
            currentSessionId = null
            currentUserId = null
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(UploadConfig.SHARD_DURATION_MS)

                val sessId = currentSessionId
                val uid = currentUserId

                if (sessId != null && uid != null) {
                    val nextSeq = currentSeqIndex + 1

                    // 1. Rotate Services
                    physicalService?.rotateLog(File(context.filesDir, "PHYS_${sessId}_$nextSeq.jsonl"))
                    ScreenService.get()?.rotateLog(File(context.filesDir, "SCREEN_${sessId}_$nextSeq.jsonl"))
                    audioService?.rotateRecording(File(context.filesDir, "AUDIO_${sessId}_$nextSeq.m4a"))

                    // 2. Seal Old
                    sealShard(uid, sessId, currentSeqIndex, isFinal = false)

                    // 3. Update State
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

    private suspend fun sealShard(userId: String, sessionId: String, seq: Int, isFinal: Boolean) {
        val traceId = UUID.randomUUID().toString()
        val dao = db.uploadDao()

        // Helper to check and queue
        suspend fun queueIfExists(type: String, filename: String) {
            val path = File(context.filesDir, filename).absolutePath
            if (File(path).exists()) {
                dao.insertQueueItem(UploadQueueEntity(
                    user_id = userId, session_id = sessionId, seq_index = seq,
                    trace_id = traceId, file_path = path, file_type = type
                ))
            }
        }

        queueIfExists("PHYS_LOG", "PHYS_${sessionId}_$seq.jsonl")
        queueIfExists("SCREEN_LOG", "SCREEN_${sessionId}_$seq.jsonl")
        queueIfExists("AUDIO", "AUDIO_${sessionId}_$seq.m4a")

        // Trigger Workers
        val inputData = Data.Builder().putString("USER_ID", userId).build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "context_worker_$userId", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ContextUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(UploadConfig.TAG_CONTEXT)
                .setInputData(inputData).build()
        )

        WorkManager.getInstance(context).enqueueUniqueWork(
            "heavy_worker_$userId", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<HeavyUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .addTag(UploadConfig.TAG_HEAVY)
                .setInputData(inputData).build()
        )
    }

    private fun log(event: String, sid: String, seq: Int, extras: Map<String, Any>) {
        val json = JSONObject()
        json.put("event", event)
        json.put("session_id", sid)
        json.put("seq_index", seq)
        json.put("ts", System.currentTimeMillis())
        extras.forEach { (k, v) -> json.put(k, v) }
        Log.i(TAG, json.toString())
    }

    companion object {
        const val TAG = "MirrorSession"
    }
}