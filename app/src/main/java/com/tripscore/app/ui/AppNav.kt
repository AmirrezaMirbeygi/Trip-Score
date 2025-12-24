package com.tripscore.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tripscore.app.service.TripRecorderService
import com.tripscore.app.vm.TripsViewModel

@Composable
fun AppNav(
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val nav = rememberNavController()
    var isRecording by remember { mutableStateOf(false) }

    val service = TripRecorderService.getInstance()
    val currentTripStateFlow = service?.currentTripState
    val currentTripState = if (currentTripStateFlow != null) {
        currentTripStateFlow.collectAsState(initial = com.tripscore.app.data.CurrentTripState())
    } else {
        remember { mutableStateOf(com.tripscore.app.data.CurrentTripState()) }
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
                isRecording = isRecording,
                currentTripState = currentTripState.value
            )
        }
        composable("activeTrip") {
            ActiveTripScreen(
                onBack = { nav.popBackStack() },
                currentTripState = currentTripState.value
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
