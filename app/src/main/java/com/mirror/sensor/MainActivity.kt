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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.mirror.sensor.services.ScreenService
import com.mirror.sensor.ui.screens.MainScreen
import com.mirror.sensor.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val mainViewModel: androidx.lifecycle.ViewModel = androidx.lifecycle.viewmodel.compose.viewModel<MainViewModel>()

                Surface {
                    MainScreen(
                        // Strict Gatekeeper Logic
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
        // 1. Runtime Permissions (Mic, Location)
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

        val allRuntimeGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allRuntimeGranted) {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
            return
        }

        // 2. Accessibility Service Check
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "The Mirror needs Accessibility to see usage", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // 3. Notification Listener Check
        if (!isNotificationListenerEnabled()) {
            Toast.makeText(this, "The Mirror needs access to Notifications", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }

        // ALL GREEN -> START ENGINE
        viewModel.startService()
        Toast.makeText(this, "The Mirror is Active", Toast.LENGTH_SHORT).show()
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            if (!micGranted) {
                Toast.makeText(this, "Microphone is critical!", Toast.LENGTH_LONG).show()
            }
            // User can try clicking Start again to proceed
        }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${ScreenService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(service) == true
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }
}