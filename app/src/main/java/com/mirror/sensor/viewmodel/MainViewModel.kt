package com.mirror.sensor.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.mirror.sensor.services.HolisticSensorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    // Called ONLY after MainActivity confirms all permissions
    fun startService() {
        val context = getApplication<Application>()
        val intent = Intent(context, HolisticSensorService::class.java)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _isServiceRunning.value = true
    }

    fun stopService() {
        val context = getApplication<Application>()
        val intent = Intent(context, HolisticSensorService::class.java)
        context.stopService(intent)
        _isServiceRunning.value = false
    }
}