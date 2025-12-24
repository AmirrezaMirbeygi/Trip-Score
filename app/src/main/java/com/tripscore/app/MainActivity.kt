package com.tripscore.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.tripscore.app.service.TripRecorderService
import com.tripscore.app.service.TripServiceController
import com.tripscore.app.ui.AppNav

class MainActivity : ComponentActivity() {

    private val reqPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Start service if permissions were granted
        if (permissions.values.any { it }) {
            if (hasLocationPermission() && !TripServiceController.isRunning(this)) {
                TripServiceController.start(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Surface(color = Color.Black) {
                MaterialTheme {
                    AppNav(
                        hasLocationPermission = hasLocationPermission(),
                        onRequestPermissions = { requestAllPermissions() },
                        onStartRecording = { TripServiceController.start(this) },
                        onStopRecording = { TripServiceController.stop(this) },
                        isServiceRunning = TripServiceController.isRunning(this)
                    )
                }
            }

            LaunchedEffect(Unit) {
                // Auto-start service if permissions are granted
                // Service will continue running even if app is closed
                if (hasLocationPermission()) {
                    if (!TripServiceController.isRunning(this@MainActivity)) {
                        TripServiceController.start(this@MainActivity)
                    }
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PermissionChecker.PERMISSION_GRANTED || coarse == PermissionChecker.PERMISSION_GRANTED
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        reqPermissions.launch(perms.toTypedArray())
    }
    
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Track touch events for distraction detection
        if (ev?.action == MotionEvent.ACTION_DOWN || ev?.action == MotionEvent.ACTION_UP) {
            TripRecorderService.getInstance()?.recordTouchEvent()
        }
        return super.dispatchTouchEvent(ev)
    }
}
