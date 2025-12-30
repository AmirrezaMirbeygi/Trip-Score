package com.goofyapps.tripscore.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.goofyapps.tripscore.service.TripRecorderService
import com.goofyapps.tripscore.vm.TripsViewModel

@Composable
fun AppNav(
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    isServiceRunning: Boolean = false
) {
    val nav = rememberNavController()
    var isRecording by remember { mutableStateOf(isServiceRunning) }
    val coroutineScope = rememberCoroutineScope()

    // Use a state to track service instance and trigger recomposition when it changes
    var service by remember { mutableStateOf(TripRecorderService.getInstance()) }
    
    // Periodically check for service instance in case it wasn't ready initially
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val newService = TripRecorderService.getInstance()
            if (newService != null && newService != service) {
                service = newService
            }
        }
    }
    
    // Collect StateFlow - this will automatically recompose when values change
    val currentTripStateFlow = service?.currentTripState
    val currentTripState by if (currentTripStateFlow != null) {
        currentTripStateFlow.collectAsState(initial = com.goofyapps.tripscore.data.CurrentTripState())
    } else {
        remember { mutableStateOf(com.goofyapps.tripscore.data.CurrentTripState()) }
    }
    
    val liveLocationPointsFlow = service?.liveLocationPoints
    val liveLocationPoints by if (liveLocationPointsFlow != null) {
        liveLocationPointsFlow.collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList<com.goofyapps.tripscore.service.TripRecorderService.LocationData>()) }
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                hasLocationPermission = hasLocationPermission,
                onRequestPermissions = onRequestPermissions,
                onStartRecording = {
                    isRecording = true
                    onStartRecording()
                },
                onStopRecording = {
                    isRecording = false
                    onStopRecording()
                },
                onOpenTrips = { nav.navigate("trips") },
                onOpenActiveTrip = { nav.navigate("activeTrip") },
                onStartTest = {
                    isRecording = true
                    service?.startTestTrip() ?: run {
                        // If service not ready, start it first
                        onStartRecording()
                        coroutineScope.launch {
                            delay(500)
                            TripRecorderService.getInstance()?.startTestTrip()
                        }
                    }
                },
                onStartTestHardBrake = {
                    isRecording = true
                    service?.startTestTripHardBrake() ?: run {
                        // If service not ready, start it first
                        onStartRecording()
                        coroutineScope.launch {
                            delay(500)
                            TripRecorderService.getInstance()?.startTestTripHardBrake()
                        }
                    }
                },
                onStartTestCornering = {
                    isRecording = true
                    service?.startTestTripCornering() ?: run {
                        // If service not ready, start it first
                        onStartRecording()
                        coroutineScope.launch {
                            delay(500)
                            TripRecorderService.getInstance()?.startTestTripCornering()
                        }
                    }
                },
                isRecording = isRecording,
                currentTripState = currentTripState,
                liveLocationPoints = liveLocationPoints.map { point ->
                    com.goofyapps.tripscore.ui.LiveLocationPoint(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        timestamp = point.timestamp,
                        speed = point.speed,
                        bearing = point.bearing
                    )
                }
            )
        }
        composable("activeTrip") {
            ActiveTripScreen(
                onBack = { 
                    // Navigate to home explicitly, but only if not already there
                    if (nav.currentDestination?.route != "home") {
                        nav.navigate("home") {
                            // Clear back stack up to home
                            popUpTo("home") { inclusive = false }
                            // Prevent multiple navigations
                            launchSingleTop = true
                        }
                    }
                },
                currentTripState = currentTripState,
                onEndTrip = {
                    // Trip ended, navigate to home explicitly, but only if not already there
                    if (nav.currentDestination?.route != "home") {
                        nav.navigate("home") {
                            // Clear back stack up to home
                            popUpTo("home") { inclusive = false }
                            // Prevent multiple navigations
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
        composable("trips") {
            val tripsVm: TripsViewModel = viewModel()
            TripsScreen(
                onBack = { nav.popBackStack() },
                onOpenTrip = { id -> nav.navigate("trip/$id") },
                onDeleteTrip = { id ->
                    tripsVm.deleteTrip(id)
                },
                vm = tripsVm
            )
        }
        composable("trip/{id}") { backStack ->
            val id = backStack.arguments?.getString("id")?.toLongOrNull() ?: -1L
            val tripsVm: TripsViewModel = viewModel()
            TripDetailScreen(
                tripId = id,
                onBack = { nav.popBackStack() },
                onDelete = { tripId ->
                    tripsVm.deleteTrip(tripId)
                }
            )
        }
    }
}

