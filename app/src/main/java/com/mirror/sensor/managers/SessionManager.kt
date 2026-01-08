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
import com.mirror.sensor.services.AudioService // NEW
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

    // Service Bindings
    private var physicalService: PhysicalService? = null
    private var audioService: AudioService? = null // NEW

    private val physConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            physicalService = (service as PhysicalService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(arg0: ComponentName) { physicalService = null }
    }

    private val audioConnection = object : ServiceConnection { // NEW
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            audioService = (service as AudioService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(arg0: ComponentName) { audioService = null }
    }

    init {
        // Bind Physical
        context.bindService(Intent(context, PhysicalService::class.java), physConnection, Context.BIND_AUTO_CREATE)
        // Bind Audio
        context.bindService(Intent(context, AudioService::class.java), audioConnection, Context.BIND_AUTO_CREATE)

        scope.launch {
            val dao = db.uploadDao()
            dao.resetStuckUploads()

            val savedState = dao.getActiveSession()
            if (savedState != null && savedState.is_active) {
                currentUserId = savedState.user_id
                currentSessionId = savedState.session_id
                currentSeqIndex = savedState.last_seq_index + 1

                log("SESSION_RESUMED", currentSessionId!!, currentSeqIndex, emptyMap())
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

        // 2. Screen
        ScreenService.get()?.startLogging(File(context.filesDir, "SCREEN_${sessId}_$seq.jsonl"))

        // 3. Audio (NEW)
        audioService?.startRecording(File(context.filesDir, "AUDIO_${sessId}_$seq.m4a"))
    }

    fun stopSession() {
        scope.launch {
            val sessionId = currentSessionId ?: return@launch

            physicalService?.stopLogging()
            ScreenService.get()?.stopLogging()
            audioService?.stopRecording() // NEW

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

                    // Rotate All Services
                    physicalService?.rotateLog(File(context.filesDir, "PHYS_${sessId}_$nextSeq.jsonl"))
                    ScreenService.get()?.rotateLog(File(context.filesDir, "SCREEN_${sessId}_$nextSeq.jsonl"))
                    audioService?.rotateRecording(File(context.filesDir, "AUDIO_${sessId}_$nextSeq.m4a")) // NEW

                    // Seal Old
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

    private suspend fun sealShard(userId: String, sessionId: String, seq: Int, isFinal: Boolean) {
        val traceId = UUID.randomUUID().toString()
        val dao = db.uploadDao()

        // FIX: Added 'suspend' keyword here so it can call dao.insertQueueItem
        suspend fun queueIfExists(type: String, filename: String) {
            val path = File(context.filesDir, filename).absolutePath
            if (File(path).exists()) {
                dao.insertQueueItem(UploadQueueEntity(
                    user_id = userId,
                    session_id = sessionId,
                    seq_index = seq,
                    trace_id = traceId,
                    file_path = path,
                    file_type = type
                ))
            }
        }

        queueIfExists("PHYS_LOG", "PHYS_${sessionId}_$seq.jsonl")
        queueIfExists("SCREEN_LOG", "SCREEN_${sessionId}_$seq.jsonl")
        queueIfExists("AUDIO", "AUDIO_${sessionId}_$seq.m4a")

        // Trigger Workers
        val inputData = Data.Builder().putString("USER_ID", userId).build()

        // Context Worker
        WorkManager.getInstance(context).enqueueUniqueWork(
            "context_worker_$userId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ContextUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(UploadConfig.TAG_CONTEXT)
                .setInputData(inputData)
                .build()
        )

        // Heavy Worker
        WorkManager.getInstance(context).enqueueUniqueWork(
            "heavy_worker_$userId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<HeavyUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .addTag(UploadConfig.TAG_HEAVY)
                .setInputData(inputData)
                .build()
        )
    }

    private fun log(event: String, sid: String, seq: Int, extras: Map<String, Any>) {
        val json = JSONObject()
        json.put("event", event)
        json.put("session_id", sid)
        json.put("seq_index", seq)
        json.put("ts", System.currentTimeMillis())
        extras.forEach { (k, v) -> json.put(k, v) }
        Log.i("MirrorSession", json.toString())
    }
}