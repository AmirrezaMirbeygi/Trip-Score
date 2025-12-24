package com.tripscore.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

data class LiveLocationPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val speed: Float,
    val bearing: Float
)

@Composable
fun LiveTripMapView(
    locationPoints: List<LiveLocationPoint>,
    modifier: Modifier = Modifier
) {
    if (locationPoints.isEmpty()) {
        return
    }

    val currentLocation = if (locationPoints.isNotEmpty()) {
        val last = locationPoints.last()
        LatLng(last.latitude, last.longitude)
    } else {
        LatLng(0.0, 0.0)
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLocation, 15f)
    }

    // Update camera to follow the current location
    LaunchedEffect(locationPoints.size) {
        if (locationPoints.isNotEmpty()) {
            val last = locationPoints.last()
            val newPosition = LatLng(last.latitude, last.longitude)
            val currentZoom = cameraPositionState.position.zoom
            val cameraUpdate = CameraUpdateFactory.newCameraPosition(
                CameraPosition.fromLatLngZoom(newPosition, currentZoom)
            )
            cameraPositionState.animate(cameraUpdate)
        }
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            isMyLocationEnabled = false,
            mapType = MapType.NORMAL
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = true,
            myLocationButtonEnabled = false
        )
    ) {
        // Draw route polyline
        if (locationPoints.size > 1) {
            val routePoints = locationPoints.map { LatLng(it.latitude, it.longitude) }
            Polyline(
                points = routePoints,
                color = androidx.compose.ui.graphics.Color(0xFF2196F3), // Blue
                width = 10f
            )
        }

        // Start marker (first point)
        if (locationPoints.isNotEmpty()) {
            val startPoint = LatLng(locationPoints.first().latitude, locationPoints.first().longitude)
            Marker(
                state = MarkerState(position = startPoint),
                title = "Trip Start",
                snippet = "Your trip started here"
            )
        }

        // Current location marker (last point)
        if (locationPoints.isNotEmpty()) {
            Marker(
                state = MarkerState(position = currentLocation),
                title = "Current Location",
                snippet = "You are here"
            )
        }
    }
}

