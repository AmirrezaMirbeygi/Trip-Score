package com.goofyapps.tripscore

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.goofyapps.tripscore.service.TripRecorderService
import com.goofyapps.tripscore.service.TripServiceController
import com.goofyapps.tripscore.ui.AppNav

class MainActivity : ComponentActivity() {

    // Callback to update permission state in composable
    private var updatePermissionState: (() -> Unit)? = null

    private val reqPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Immediately trigger permission check when result comes back
        updatePermissionState?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Surface(color = Color.Black) {
                MaterialTheme {
                    // Use a reactive state that checks permissions
                    var hasPermission by remember { mutableStateOf(hasLocationPermission()) }
                    val lifecycleOwner = LocalLifecycleOwner.current
                    
                    // Store callback to update permission state
                    LaunchedEffect(Unit) {
                        updatePermissionState = {
                            hasPermission = hasLocationPermission()
                        }
                    }
                    
                    // Check permissions when activity resumes (after permission dialog)
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                hasPermission = hasLocationPermission()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }
                    
                    // Periodically check permissions to catch any changes
                    LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(500) // Check every 500ms
                            val current = hasLocationPermission()
                            if (current != hasPermission) {
                                hasPermission = current
                            }
                        }
                    }
                    
                    // Track permission request to trigger additional checks
                    var permissionRequestKey by remember { mutableStateOf(0) }
                    LaunchedEffect(permissionRequestKey) {
                        if (permissionRequestKey > 0) {
                            // Check immediately and then periodically after request
                            hasPermission = hasLocationPermission()
                            repeat(15) {
                                kotlinx.coroutines.delay(200)
                                val current = hasLocationPermission()
                                if (current != hasPermission) {
                                    hasPermission = current
                                }
                            }
                        }
                    }
                    
                    AppNav(
                        hasLocationPermission = hasPermission,
                        onRequestPermissions = { 
                            // Check immediately when button is clicked
                            hasPermission = hasLocationPermission()
                            permissionRequestKey++
                            requestAllPermissions() 
                        },
                        onStartRecording = { TripServiceController.start(this) },
                        onStopRecording = { TripServiceController.stop(this) },
                        isServiceRunning = TripServiceController.isRunning(this)
                    )
                }
            }

            // Don't auto-start service - only start when user explicitly presses "Start Recording"
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
