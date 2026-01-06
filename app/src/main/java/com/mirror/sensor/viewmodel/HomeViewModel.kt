package com.mirror.sensor.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mirror.sensor.data.model.Memory
import com.mirror.sensor.managers.RealTimeSensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date

class HomeViewModel : ViewModel() {

    private val db = FirebaseFirestore.getInstance()

    // 1. MEMORIES STATE
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories

    // 2. SELECTED DATE STATE (Persists across navigation)
    private val _selectedDate = MutableStateFlow(Date())
    val selectedDate: StateFlow<Date> = _selectedDate

    // 3. LIVE AUDIO
    val audioLevel = RealTimeSensorManager.audioLevel

    init {
        subscribeToConsciousness()
    }

    fun updateSelectedDate(date: Date) {
        _selectedDate.value = date
    }

    private fun subscribeToConsciousness() {
        db.collection("memories")
            .orderBy("anchor_date", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("TheMirrorBrain", "Listen failed.", e)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val memoryList = snapshot.documents.mapNotNull { doc ->
                        try {
                            doc.toObject(Memory::class.java)?.copy(id = doc.id)
                        } catch (e: Exception) { null }
                    }
                    _memories.value = memoryList
                }
            }
    }
}