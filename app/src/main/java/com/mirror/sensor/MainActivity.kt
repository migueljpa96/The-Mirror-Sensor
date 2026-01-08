package com.mirror.sensor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mirror.sensor.ui.screens.MainScreen
import com.mirror.sensor.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val mainViewModel: MainViewModel = viewModel()
                Surface {
                    MainScreen(
                        onToggleService = { isRunning ->
                            if (isRunning) mainViewModel.stopService()
                            else checkAllPermissionsAndStart(mainViewModel)
                        },
                        viewModel = mainViewModel
                    )
                }
            }
        }
    }

    private fun checkAllPermissionsAndStart(viewModel: MainViewModel) {
        // 1. RUNTIME PERMISSIONS ONLY
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allRuntimeGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allRuntimeGranted) {
            Toast.makeText(this, "Permissions missing. Re-run onboarding.", Toast.LENGTH_LONG).show()
            return
        }

        // ALL GREEN -> START
        viewModel.startService()
        Toast.makeText(this, "The Mirror is Active", Toast.LENGTH_SHORT).show()
    }
}