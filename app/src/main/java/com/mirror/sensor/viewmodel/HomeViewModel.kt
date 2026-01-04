package com.mirror.sensor.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mirror.sensor.data.model.Memory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class HomeViewModel : ViewModel() {

    private val db = FirebaseFirestore.getInstance()

    // The Stream of Consciousness (UI State)
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories

    init {
        subscribeToConsciousness()
    }

    private fun subscribeToConsciousness() {
        // Listen to the "memories" collection in real-time
        db.collection("memories")
            .orderBy("anchor_date", Query.Direction.DESCENDING)
            .limit(50) // Keep the stream lightweight
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("TheMirrorBrain", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val memoryList = snapshot.documents.mapNotNull { doc ->
                        try {
                            doc.toObject(Memory::class.java)?.copy(id = doc.id)
                        } catch (e: Exception) {
                            Log.w("TheMirrorBrain", "Malformed memory: ${doc.id}", e)
                            null
                        }
                    }
                    _memories.value = memoryList
                }
            }
    }
}