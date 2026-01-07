package com.mirror.sensor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.mirror.sensor.ui.screens.MainScreen
import com.mirror.sensor.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Manually scoped to Activity to survive Navigation
                val mainViewModel: androidx.lifecycle.ViewModel = androidx.lifecycle.viewmodel.compose.viewModel<MainViewModel>()

                Surface {
                    MainScreen(
                        onToggleService = { isRunning ->
                            if (isRunning) {
                                (mainViewModel as MainViewModel).stopService()
                            } else {
                                checkAllPermissionsAndStart(mainViewModel as MainViewModel)
                            }
                        },
                        viewModel = mainViewModel as MainViewModel
                    )
                }
            }
        }
    }

    private fun checkAllPermissionsAndStart(viewModel: MainViewModel) {
        // 1. Runtime Permissions (Mic, Location, Physical)
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            Toast.makeText(this, "Permissions missing. Re-run onboarding.", Toast.LENGTH_LONG).show()
            // In a real app, you might want to reset onboarding flag or show dialog
            return
        }

        // 2. Notification Listener Check (Optional, but kept for NotificationService)
        // If user didn't enable it, we just warn or skip
        /* if (!isNotificationListenerEnabled()) {
            Toast.makeText(this, "Notification access for digital context is missing", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        */

        // ALL GREEN -> START ENGINE
        viewModel.startService()
        Toast.makeText(this, "The Mirror is Active", Toast.LENGTH_SHORT).show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }
}