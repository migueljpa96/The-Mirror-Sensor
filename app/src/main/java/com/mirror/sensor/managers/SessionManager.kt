package com.mirror.sensor.managers

import android.content.Context
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

    init {
        scope.launch {
            val dao = db.uploadDao()

            // 1. Crash Recovery
            val resetCount = dao.resetStuckUploads()
            if (resetCount > 0) log("CRASH_RECOVERY", "SYSTEM", -1, mapOf("reset" to resetCount))

            // 2. Resume Session (Sequence Guard)
            val savedState = dao.getActiveSession()
            if (savedState != null && savedState.is_active) {
                currentUserId = savedState.user_id
                currentSessionId = savedState.session_id

                // HARDENING: Sequence Guard
                // We trust the DB state, but strictly ensure we increment from the last known point.
                currentSeqIndex = savedState.last_seq_index

                log("SESSION_RESUMED", currentSessionId!!, currentSeqIndex, emptyMap())
                startTicker()
            }
        }
    }

    // HARDENING: userId is now mandatory to start a session
    fun startSession(userId: String) {
        if (!UploadConfig.SESSION_CHUNKING_ENABLED) return

        scope.launch {
            if (currentSessionId != null) {
                // Identity Check: Can't overwrite active session of another user
                if (currentUserId != userId) {
                    log("ERROR", "SYSTEM", -1, mapOf("msg" to "User mismatch on start"))
                }
                return@launch
            }

            currentUserId = userId
            val newId = "sess_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 4)}"
            currentSessionId = newId
            currentSeqIndex = 0

            db.uploadDao().setSessionState(SessionStateEntity(
                user_id = userId,
                session_id = newId,
                start_ts = System.currentTimeMillis(),
                last_seq_index = 0,
                is_active = true
            ))

            log("SESSION_STARTED", newId, 0, mapOf("uid" to userId))
            startTicker()
        }
    }

    fun stopSession() {
        scope.launch {
            val sessionId = currentSessionId ?: return@launch

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
                // Capture local state to avoid race conditions if stopSession called
                val sessId = currentSessionId
                val uid = currentUserId

                if (sessId != null && uid != null) {
                    sealShard(uid, sessId, currentSeqIndex, isFinal = false)
                    currentSeqIndex++

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
        // HARDENING: Trace ID Generation
        val traceId = UUID.randomUUID().toString()
        log("SHARD_SEALING", sessionId, seq, mapOf("trace_id" to traceId, "is_final" to isFinal))

        val dao = db.uploadDao()

        // Define expected files (stubs)
        val files = listOf(
            Triple("PHYS_LOG", "PHYS_${sessionId}_$seq.jsonl", "phys"),
            Triple("SCREEN_LOG", "SCREEN_${sessionId}_$seq.jsonl", "screen"),
            Triple("AUDIO", "AUDIO_${sessionId}_$seq.m4a", "audio")
        )

        files.forEach { (type, filename, _) ->
            val path = File(context.filesDir, filename).absolutePath

            dao.insertQueueItem(UploadQueueEntity(
                user_id = userId,
                session_id = sessionId,
                seq_index = seq,
                trace_id = traceId, // All components of shard share trace_id
                file_path = path,
                file_type = type
            ))
        }

        enqueueWorkers(userId)
    }

    private fun enqueueWorkers(userId: String) {
        val inputData = Data.Builder().putString("USER_ID", userId).build()

        val contextRequest = OneTimeWorkRequestBuilder<ContextUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(UploadConfig.TAG_CONTEXT)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "context_worker_$userId", // Unique per user
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            contextRequest
        )

        val heavyRequest = OneTimeWorkRequestBuilder<HeavyUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .addTag(UploadConfig.TAG_HEAVY)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "heavy_worker_$userId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            heavyRequest
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