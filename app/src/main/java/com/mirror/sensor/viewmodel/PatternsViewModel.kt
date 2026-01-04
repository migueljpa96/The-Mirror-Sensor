package com.mirror.sensor.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mirror.sensor.data.model.DailySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PatternsViewModel : ViewModel() {

    private val db = FirebaseFirestore.getInstance()

    // UI State: The latest daily summary
    private val _latestSummary = MutableStateFlow<DailySummary?>(null)
    val latestSummary: StateFlow<DailySummary?> = _latestSummary

    init {
        listenToPatterns()
    }

    private fun listenToPatterns() {
        // Get the single most recent summary
        db.collection("daily_summaries")
            .orderBy("date", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("TheMirrorPatterns", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val doc = snapshot.documents[0]
                    try {
                        val summary = doc.toObject(DailySummary::class.java)
                        _latestSummary.value = summary
                    } catch (e: Exception) {
                        Log.e("TheMirrorPatterns", "Malformed summary", e)
                    }
                }
            }
    }
}