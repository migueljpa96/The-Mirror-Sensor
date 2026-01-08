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

class ContextUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        val targetUserId = inputData.getString("USER_ID") ?: return Result.failure()
        val dao = AppDatabase.getDatabase(applicationContext).uploadDao()

        // 1. Fetch Context items (Logs) bound to this user
        val pendingItems = dao.getItemsByStatus("PENDING").filter {
            it.user_id == targetUserId &&
                    (it.file_type == "PHYS_LOG" || it.file_type == "SCREEN_LOG")
        }

        if (pendingItems.isEmpty()) return Result.success()

        pendingItems.forEach { item ->
            if (!uploadItem(dao, item)) return Result.retry()
        }
        return Result.success()
    }

    private suspend fun uploadItem(dao: com.mirror.sensor.data.db.UploadDao, item: UploadQueueEntity): Boolean {
        // Mark UPLOADING
        dao.update(item.copy(status = "UPLOADING"))

        val file = File(item.file_path)
        if (!file.exists()) {
            Log.e(TAG, "❌ File not found: ${item.file_path}")
            // Fail permanently to unblock queue
            dao.update(item.copy(status = "FAILED_PERM"))
            return true
        }

        return try {
            // Construct Path: users/{uid}/sessions/{session_id}/{filename}
            val filename = file.name
            val cloudPath = "users/${item.user_id}/sessions/${item.session_id}/$filename"
            val ref = storage.reference.child(cloudPath)

            // Attach Metadata for the Cloud Assembler
            val metadata = StorageMetadata.Builder()
                .setCustomMetadata("trace_id", item.trace_id)
                .setCustomMetadata("seq_index", item.seq_index.toString())
                .setCustomMetadata("type", item.file_type)
                .build()

            // Upload
            ref.putFile(Uri.fromFile(file), metadata).await()

            // Success: Delete local file and update DB
            file.delete()
            dao.update(item.copy(status = "COMPLETED", attempts = item.attempts + 1))
            Log.i(TAG, "✅ Uploaded Context: $filename")
            true

        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Upload Failed: ${item.file_path}", e)

            // Calculate Backoff
            val nextAttempts = item.attempts + 1
            val nextDelay = UploadConfig.getContextDelay(nextAttempts)

            dao.update(item.copy(
                status = "PENDING",
                attempts = nextAttempts,
                retry_after = System.currentTimeMillis() + nextDelay
            ))
            false // Signal retry
        }
    }

    companion object {
        const val TAG = "MirrorContextUpload"
    }
}

class HeavyUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        val targetUserId = inputData.getString("USER_ID") ?: return Result.failure()
        val dao = AppDatabase.getDatabase(applicationContext).uploadDao()

        // 1. Fetch Heavy items (Audio) bound to this user
        val pendingItems = dao.getItemsByStatus("PENDING").filter {
            it.user_id == targetUserId && it.file_type == "AUDIO"
        }

        if (pendingItems.isEmpty()) return Result.success()

        pendingItems.forEach { item ->
            // Check manual backoff
            if (System.currentTimeMillis() < item.retry_after) return@forEach

            if (!uploadItem(dao, item)) return Result.retry()
        }
        return Result.success()
    }

    private suspend fun uploadItem(dao: com.mirror.sensor.data.db.UploadDao, item: UploadQueueEntity): Boolean {
        dao.update(item.copy(status = "UPLOADING"))

        val file = File(item.file_path)
        if (!file.exists()) {
            Log.e(TAG, "❌ Audio File not found: ${item.file_path}")
            dao.update(item.copy(status = "FAILED_PERM"))
            return true
        }

        return try {
            val filename = file.name
            val cloudPath = "users/${item.user_id}/sessions/${item.session_id}/$filename"
            val ref = storage.reference.child(cloudPath)

            // Metadata including Silence Default
            val metadata = StorageMetadata.Builder()
                .setCustomMetadata("trace_id", item.trace_id)
                .setCustomMetadata("seq_index", item.seq_index.toString())
                .setCustomMetadata("type", item.file_type)
                .setCustomMetadata("is_silent", "false") // Default for now
                .build()

            // Upload
            ref.putFile(Uri.fromFile(file), metadata).await()

            // Success
            file.delete()
            dao.update(item.copy(status = "COMPLETED", attempts = item.attempts + 1))
            Log.i(TAG, "✅ Uploaded Heavy: $filename")
            true

        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Audio Upload Failed: ${item.file_path}", e)

            val nextAttempts = item.attempts + 1
            val nextDelay = UploadConfig.getHeavyDelay(nextAttempts)

            dao.update(item.copy(
                status = "PENDING",
                attempts = nextAttempts,
                retry_after = System.currentTimeMillis() + nextDelay
            ))
            false
        }
    }

    companion object {
        const val TAG = "MirrorHeavyUpload"
    }
}