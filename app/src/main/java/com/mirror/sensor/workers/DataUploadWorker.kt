package com.mirror.sensor.workers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

class DataUploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        Log.i(TAG, "🚀 Worker Started")

        // 1. SAFETY PAUSE (The "Renaming Buffer")
        // We give the BroadcastReceivers (Screen/Notifs) 2 seconds to finish renaming
        // their temp files to TIMESTAMP_ID before we list the directory.
        delay(2000)

        // Use 'null' to scan the root files directory
        val filesDir = applicationContext.getExternalFilesDir(null) ?: return Result.failure()

        // 2. SORTED LISTING (Context First, Trigger Last)
        // We filter out temp files immediately, then sort so .m4a comes LAST.
        val allFiles = filesDir.listFiles()
            ?.filter { !it.name.startsWith("temp_") && it.length() > 0 }
            ?.sortedWith(Comparator { f1, f2 ->
                val isAudio1 = f1.name.endsWith(".m4a")
                val isAudio2 = f2.name.endsWith(".m4a")

                when {
                    // If f1 is audio and f2 is not, f1 goes LAST (return 1)
                    isAudio1 && !isAudio2 -> 1
                    // If f1 is not audio and f2 is, f1 goes FIRST (return -1)
                    !isAudio1 && isAudio2 -> -1
                    // Otherwise sort alphabetically
                    else -> f1.name.compareTo(f2.name)
                }
            })
            ?: emptyList()

        if (allFiles.isEmpty()) {
            Log.d(TAG, "📭 No files to upload.")
            return Result.success()
        }

        var uploadCount = 0

        for (file in allFiles) {
            try {
                // 3. ROUTING
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

                // 4. BLOCKING UPLOAD
                // Because of the sort above, we are guaranteed to upload Context logs
                // BEFORE we upload the Audio file (which triggers the Cloud Function).
                storageRef.putFile(fileUri).await()

                if (file.delete()) {
                    Log.d(TAG, "🗑️ Local Delete: ${file.name}")
                    uploadCount++
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Upload Failed: ${file.name}", e)
                // Retry next time
            }
        }

        Log.i(TAG, "✅ Job Done. Uploaded: $uploadCount")
        return Result.success()
    }

    companion object {
        const val TAG = "TheMirrorUpload"
    }
}