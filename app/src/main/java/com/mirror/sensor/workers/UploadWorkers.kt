package com.mirror.sensor.workers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.mirror.sensor.data.UploadConfig
import com.mirror.sensor.data.db.AppDatabase
import com.mirror.sensor.data.db.UploadQueueEntity
import kotlinx.coroutines.tasks.await
import java.io.File

// Increased scan size to ensure we find relevant items even in a mixed queue
private const val SCAN_LIMIT = 50

/**
 * Worker 1: ContextUploadWorker
 * Handles: PHYS_LOG, SCREEN_LOG, CONTEXT_SUMMARY
 */
class ContextUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        val targetUserId = inputData.getString("USER_ID") ?: return Result.failure()
        val dao = AppDatabase.getDatabase(applicationContext).uploadDao()

        // 1. Fetch a larger batch of candidates to avoid starvation
        val allCandidates = dao.getItemsByStatus("PENDING", SCAN_LIMIT)

        // 2. Filter for relevant types
        val workItems = allCandidates.filter {
            it.user_id == targetUserId &&
                    (it.file_type == "PHYS_LOG" ||
                            it.file_type == "SCREEN_LOG" ||
                            it.file_type == "CONTEXT_SUMMARY")
        }

        if (workItems.isEmpty()) return Result.success()

        var hasFailures = false
        // Process up to 20 items per run to be respectful of resources
        workItems.take(20).forEach { item ->
            if (!uploadItem(dao, item)) hasFailures = true
        }

        // If we found work, we might have more. If failed, retry.
        return if (hasFailures) Result.retry() else Result.success()
    }

    private suspend fun uploadItem(dao: com.mirror.sensor.data.db.UploadDao, item: UploadQueueEntity): Boolean {
        dao.update(item.copy(status = "UPLOADING"))

        val file = File(item.file_path)
        if (!file.exists()) {
            dao.update(item.copy(status = "FAILED_PERM"))
            return true
        }

        return try {
            val ref = storage.reference.child("users/${item.user_id}/sessions/${item.session_id}/${file.name}")

            val metaBuilder = StorageMetadata.Builder()
                .setCustomMetadata("trace_id", item.trace_id)
                .setCustomMetadata("seq_index", item.seq_index.toString())
                .setCustomMetadata("type", item.file_type)

            if (item.file_type == "CONTEXT_SUMMARY") {
                metaBuilder.setContentType("text/plain")
            }

            ref.putFile(Uri.fromFile(file), metaBuilder.build()).await()

            file.delete()
            dao.update(item.copy(status = "COMPLETED", attempts = item.attempts + 1))
            Log.i(TAG, "✅ Uploaded Context: ${file.name}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Upload Failed: ${item.file_path}", e)
            val nextAttempts = item.attempts + 1
            dao.update(item.copy(
                status = "PENDING",
                attempts = nextAttempts,
                retry_after = System.currentTimeMillis() + UploadConfig.getContextDelay(nextAttempts)
            ))
            false
        }
    }

    companion object { const val TAG = "MirrorContextUpload" }
}

/**
 * Worker 2: HeavyUploadWorker
 * Handles: AUDIO
 */
class HeavyUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        val targetUserId = inputData.getString("USER_ID") ?: return Result.failure()
        val dao = AppDatabase.getDatabase(applicationContext).uploadDao()

        // 1. Fetch larger batch to find Audio files buried behind logs
        val allCandidates = dao.getItemsByStatus("PENDING", SCAN_LIMIT)

        // 2. Filter for AUDIO
        val workItems = allCandidates.filter {
            it.user_id == targetUserId && it.file_type == "AUDIO"
        }

        if (workItems.isEmpty()) return Result.success()

        var hasFailures = false
        // Process up to 5 audio files per run
        workItems.take(5).forEach { item ->
            if (System.currentTimeMillis() < item.retry_after) return@forEach
            if (!uploadItem(dao, item)) hasFailures = true
        }
        return if (hasFailures) Result.retry() else Result.success()
    }

    private suspend fun uploadItem(dao: com.mirror.sensor.data.db.UploadDao, item: UploadQueueEntity): Boolean {
        dao.update(item.copy(status = "UPLOADING"))

        val file = File(item.file_path)
        if (!file.exists()) {
            dao.update(item.copy(status = "FAILED_PERM"))
            return true
        }

        return try {
            val ref = storage.reference.child("users/${item.user_id}/sessions/${item.session_id}/${file.name}")

            val metadata = StorageMetadata.Builder()
                .setCustomMetadata("trace_id", item.trace_id)
                .setCustomMetadata("seq_index", item.seq_index.toString())
                .setCustomMetadata("type", item.file_type)
                .setCustomMetadata("is_silent", "false")
                .build()

            ref.putFile(Uri.fromFile(file), metadata).await()

            file.delete()
            dao.update(item.copy(status = "COMPLETED", attempts = item.attempts + 1))
            Log.i(TAG, "✅ Uploaded Heavy: ${file.name}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Audio Upload Failed", e)
            val nextAttempts = item.attempts + 1
            dao.update(item.copy(
                status = "PENDING",
                attempts = nextAttempts,
                retry_after = System.currentTimeMillis() + UploadConfig.getHeavyDelay(nextAttempts)
            ))
            false
        }
    }

    companion object { const val TAG = "MirrorHeavyUpload" }
}