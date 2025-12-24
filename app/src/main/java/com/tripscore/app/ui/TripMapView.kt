package com.tripscore.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.tripscore.app.data.EventMarkerEntity
import com.tripscore.app.data.LocationPointEntity

@Composable
fun TripMapView(
    locationPoints: List<LocationPointEntity>,
    eventMarkers: List<EventMarkerEntity>,
    modifier: Modifier = Modifier
) {
    if (locationPoints.isEmpty()) {
        return
    }

    val startPoint = LatLng(locationPoints.first().latitude, locationPoints.first().longitude)
    val endPoint = LatLng(locationPoints.last().latitude, locationPoints.last().longitude)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(startPoint, 13f)
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        onMapLoaded = {
            // Fit bounds to show entire route
            if (locationPoints.isNotEmpty()) {
                val bounds = locationPoints.map { LatLng(it.latitude, it.longitude) }
                // Would use LatLngBounds here in a real implementation
            }
        }
    ) {
        // Draw route polyline
        if (locationPoints.size > 1) {
            val routePoints = locationPoints.map { LatLng(it.latitude, it.longitude) }
            Polyline(
                points = routePoints,
                color = androidx.compose.ui.graphics.Color(0xFF2196F3),
                width = 8f
            )
        }

        // Start marker
        Marker(
            state = MarkerState(position = startPoint),
            title = "Start",
            snippet = "Trip started here"
        )

        // End marker
        if (locationPoints.size > 1) {
            Marker(
                state = MarkerState(position = endPoint),
                title = "End",
                snippet = "Trip ended here"
            )
        }

        // Event markers
        eventMarkers.forEach { event ->
            val position = LatLng(event.latitude, event.longitude)
            val color = when (event.eventType) {
                "speeding_major", "brake_panic", "accel_aggressive", "turn_aggressive" ->
                    androidx.compose.ui.graphics.Color(0xFFF44336) // Red
                "speeding_minor", "brake_hard", "accel_moderate", "turn_sharp" ->
                    androidx.compose.ui.graphics.Color(0xFFFF9800) // Orange
                else -> androidx.compose.ui.graphics.Color(0xFF2196F3) // Blue
            }

            val icon = when (event.eventType) {
                "speeding_minor", "speeding_major" -> "🚗"
                "brake_hard", "brake_panic" -> "⚠️"
                "accel_moderate", "accel_aggressive" -> "⚡"
                "turn_sharp", "turn_aggressive" -> "↪️"
                else -> "📍"
            }

            Marker(
                state = MarkerState(position = position),
                title = event.eventType.replace("_", " ").capitalize(),
                snippet = icon
            )
        }
    }
}

