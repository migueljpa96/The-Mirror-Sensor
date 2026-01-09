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

// BATCH_SIZE limits how many files we process per 10-minute job window.
// 20 Context files (~200KB) is trivial.
private const val BATCH_SIZE_CONTEXT = 20
// 5 Heavy files (~50MB) is a safe limit for mobile upstream.
private const val BATCH_SIZE_HEAVY = 5

class ContextUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        val targetUserId = inputData.getString("USER_ID") ?: return Result.failure()
        val dao = AppDatabase.getDatabase(applicationContext).uploadDao()

        // fetch with limit
        val pendingItems = dao.getItemsByStatus("PENDING", BATCH_SIZE_CONTEXT).filter {
            it.user_id == targetUserId &&
                    (it.file_type == "PHYS_LOG" || it.file_type == "SCREEN_LOG")
        }

        if (pendingItems.isEmpty()) return Result.success()

        var hasFailures = false
        pendingItems.forEach { item ->
            val success = uploadItem(dao, item)
            if (!success) hasFailures = true
        }

        // If some failed, return Retry to trigger backoff. If all succeeded, Success.
        return if (hasFailures) Result.retry() else Result.success()
    }

    private suspend fun uploadItem(dao: com.mirror.sensor.data.db.UploadDao, item: UploadQueueEntity): Boolean {
        dao.update(item.copy(status = "UPLOADING"))

        val file = File(item.file_path)
        if (!file.exists()) {
            Log.e(TAG, "❌ File missing: ${item.file_path}. Marking FAILED_PERM.")
            // Critical: Do not retry missing files, it creates infinite loops.
            dao.update(item.copy(status = "FAILED_PERM"))
            return true
        }

        return try {
            val filename = file.name
            val cloudPath = "users/${item.user_id}/sessions/${item.session_id}/$filename"
            val ref = storage.reference.child(cloudPath)

            val metadata = StorageMetadata.Builder()
                .setCustomMetadata("trace_id", item.trace_id)
                .setCustomMetadata("seq_index", item.seq_index.toString())
                .setCustomMetadata("type", item.file_type)
                .build()

            ref.putFile(Uri.fromFile(file), metadata).await()

            file.delete()
            dao.update(item.copy(status = "COMPLETED", attempts = item.attempts + 1))
            Log.i(TAG, "✅ Uploaded Context: $filename")
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

class HeavyUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        val targetUserId = inputData.getString("USER_ID") ?: return Result.failure()
        val dao = AppDatabase.getDatabase(applicationContext).uploadDao()

        val pendingItems = dao.getItemsByStatus("PENDING", BATCH_SIZE_HEAVY).filter {
            it.user_id == targetUserId && it.file_type == "AUDIO"
        }

        if (pendingItems.isEmpty()) return Result.success()

        var hasFailures = false
        pendingItems.forEach { item ->
            if (System.currentTimeMillis() < item.retry_after) return@forEach
            if (!uploadItem(dao, item)) hasFailures = true
        }
        return if (hasFailures) Result.retry() else Result.success()
    }

    private suspend fun uploadItem(dao: com.mirror.sensor.data.db.UploadDao, item: UploadQueueEntity): Boolean {
        dao.update(item.copy(status = "UPLOADING"))

        val file = File(item.file_path)
        if (!file.exists()) {
            Log.e(TAG, "❌ Audio Missing: ${item.file_path}")
            dao.update(item.copy(status = "FAILED_PERM"))
            return true
        }

        return try {
            val filename = file.name
            val cloudPath = "users/${item.user_id}/sessions/${item.session_id}/$filename"
            val ref = storage.reference.child(cloudPath)

            val metadata = StorageMetadata.Builder()
                .setCustomMetadata("trace_id", item.trace_id)
                .setCustomMetadata("seq_index", item.seq_index.toString())
                .setCustomMetadata("type", item.file_type)
                .setCustomMetadata("is_silent", "false")
                .build()

            ref.putFile(Uri.fromFile(file), metadata).await()

            file.delete()
            dao.update(item.copy(status = "COMPLETED", attempts = item.attempts + 1))
            Log.i(TAG, "✅ Uploaded Heavy: $filename")
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