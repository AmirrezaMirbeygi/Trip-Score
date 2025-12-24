package com.tripscore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripscore.app.vm.TripDetailViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.first

@Composable
fun TripDetailScreen(
    tripId: Long,
    onBack: () -> Unit,
    onDelete: (Long) -> Unit,
    vm: TripDetailViewModel = viewModel()
) {
    val trip by vm.trip(tripId).collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Trip Details",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            onDelete(tripId)
                            onBack()
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Back")
                    }
                }
            }
        }

        if (trip == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Trip not found.", style = MaterialTheme.typography.bodyLarge)
            }
            return
        }

        val t = trip!!

        var locationPoints by remember { mutableStateOf<List<com.tripscore.app.data.LocationPointEntity>>(emptyList()) }
        var eventMarkers by remember { mutableStateOf<List<com.tripscore.app.data.EventMarkerEntity>>(emptyList()) }

        LaunchedEffect(tripId) {
            locationPoints = vm.getLocationPoints(tripId)
            eventMarkers = vm.getEventMarkers(tripId)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Map Card
            if (locationPoints.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    TripMapView(
                        locationPoints = locationPoints,
                        eventMarkers = eventMarkers,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Score Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = getScoreColor(t.score100).copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Trip Score",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(5) { index ->
                            Icon(
                                imageVector = if (index < t.scoreStars) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = if (index < t.scoreStars) getScoreColor(t.score100) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                    
                    Text(
                        text = "${t.score100.toInt()}/100",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = getScoreColor(t.score100)
                    )
                }
            }

            // Trip Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Trip Information",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    InfoRow(
                        icon = Icons.Default.CalendarToday,
                        label = "Date",
                        value = formatDate(t.startEpochMs)
                    )
                    InfoRow(
                        icon = Icons.Default.Schedule,
                        label = "Duration",
                        value = "${t.durationMin.toInt()} minutes"
                    )
                    InfoRow(
                        icon = Icons.Default.Straighten,
                        label = "Distance",
                        value = "${"%.2f".format(t.distanceKm)} km"
                    )
                    InfoRow(
                        icon = Icons.Default.Route,
                        label = "Route ID",
                        value = t.routeId.take(16) + "…"
                    )
                }
            }

            // Category Scores Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "Category Scores",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    CategoryScoreRow(
                        title = "Speed",
                        icon = Icons.Default.Speed,
                        score = calculateSpeedScore(t.minorSpeeding, t.midSpeeding, t.majorSpeeding, t.distanceKm),
                        minorCount = t.minorSpeeding,
                        majorCount = t.majorSpeeding,
                        minorLabel = "Minor",
                        majorLabel = "Major",
                        showMidCount = true,
                        midCount = t.midSpeeding
                    )

                    Divider()

                    CategoryScoreRow(
                        title = "Braking",
                        icon = Icons.Default.Warning,
                        score = calculateBrakingScore(t.minorBrakes, t.midBrakes, t.majorBrakes),
                        minorCount = t.minorBrakes,
                        majorCount = t.majorBrakes,
                        minorLabel = "Minor",
                        majorLabel = "Major",
                        showMidCount = true,
                        midCount = t.midBrakes
                    )

                    Divider()

                    CategoryScoreRow(
                        title = "Acceleration",
                        icon = Icons.Default.TrendingUp,
                        score = calculateAccelScore(t.minorAccel, t.midAccel, t.majorAccel, t.distanceKm),
                        minorCount = t.minorAccel,
                        majorCount = t.majorAccel,
                        minorLabel = "Minor",
                        majorLabel = "Major",
                        showMidCount = true,
                        midCount = t.midAccel
                    )

                    Divider()

                    CategoryScoreRow(
                        title = "Cornering",
                        icon = Icons.Default.RotateRight,
                        score = calculateCorneringScore(t.minorTurns, t.midTurns, t.majorTurns, t.distanceKm),
                        minorCount = t.minorTurns,
                        majorCount = t.majorTurns,
                        minorLabel = "Minor",
                        majorLabel = "Major",
                        showMidCount = true,
                        midCount = t.midTurns
                    )

                    Divider()

                    CategoryScoreRow(
                        title = "Duration",
                        icon = Icons.Default.Schedule,
                        score = calculateDurationScore(t.durationMin),
                        minorCount = t.durationMin.toInt(),
                        majorCount = 0,
                        showCounts = false
                    )

                    Divider()

                    CategoryScoreRow(
                        title = "Distraction",
                        icon = Icons.Default.PhoneAndroid,
                        score = calculateDistractionScore(t.handledSeconds, t.screenOnMovingSeconds, t.distanceKm),
                        minorCount = t.handledSeconds.toInt(),
                        majorCount = 0,
                        minorLabel = "Touches",
                        majorLabel = ""
                    )
                }
            }

            // Driving Events Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "Event Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    EventSection(
                        title = "Speeding",
                        icon = Icons.Default.Speed,
                        minorCount = t.minorSpeeding,
                        midCount = t.midSpeeding,
                        majorCount = t.majorSpeeding,
                        minorLabel = "Minor",
                        midLabel = "Mid",
                        majorLabel = "Major"
                    )

                    Divider()

                    EventSection(
                        title = "Braking",
                        icon = Icons.Default.Warning,
                        minorCount = t.minorBrakes,
                        midCount = t.midBrakes,
                        majorCount = t.majorBrakes,
                        minorLabel = "Minor",
                        midLabel = "Mid",
                        majorLabel = "Major"
                    )

                    Divider()

                    EventSection(
                        title = "Acceleration",
                        icon = Icons.Default.TrendingUp,
                        minorCount = t.minorAccel,
                        midCount = t.midAccel,
                        majorCount = t.majorAccel,
                        minorLabel = "Minor",
                        midLabel = "Mid",
                        majorLabel = "Major"
                    )

                    Divider()

                    EventSection(
                        title = "Cornering",
                        icon = Icons.Default.RotateRight,
                        minorCount = t.minorTurns,
                        midCount = t.midTurns,
                        majorCount = t.majorTurns,
                        minorLabel = "Minor",
                        midLabel = "Mid",
                        majorLabel = "Major"
                    )
                }
            }

            // Distraction Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (t.handledSeconds > 0)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = if (t.handledSeconds > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Distraction",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    InfoRow(
                        icon = Icons.Default.TouchApp,
                        label = "Phone Handled",
                        value = "${t.handledSeconds.toInt()} seconds"
                    )
                    InfoRow(
                        icon = Icons.Default.PhoneIphone,
                        label = "Screen On (Moving)",
                        value = "${t.screenOnMovingSeconds.toInt()} seconds"
                    )
                }
            }

            // Additional Info Card
            if (t.nightMinutes > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Night Driving",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${t.nightMinutes.toInt()} minutes",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EventSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    minorCount: Int,
    majorCount: Int,
    minorLabel: String,
    majorLabel: String,
    midCount: Int = 0,
    midLabel: String = "Mid"
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            EventBadge(
                label = minorLabel,
                count = minorCount,
                color = if (minorCount > 0) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (midCount > 0 || midLabel.isNotEmpty()) {
                EventBadge(
                    label = midLabel,
                    count = midCount,
                    color = if (midCount > 0) Color(0xFFFF5722) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            EventBadge(
                label = majorLabel,
                count = majorCount,
                color = if (majorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EventBadge(label: String, count: Int, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CategoryScoreRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    score: Double,
    minorCount: Int,
    majorCount: Int,
    showCounts: Boolean = true,
    minorLabel: String = "minor",
    majorLabel: String = "major",
    showMidCount: Boolean = false,
    midCount: Int = 0
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (showCounts) {
                    if (minorCount > 0 || midCount > 0 || majorCount > 0) {
                        val counts = if (showMidCount) {
                            "$minorCount minor, $midCount mid, $majorCount $majorLabel"
                        } else {
                            "$minorCount $minorLabel, $majorCount $majorLabel"
                        }
                        Text(
                            text = counts,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "No events",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // For duration, show duration info
                    Text(
                        text = "${(minorCount + majorCount).coerceAtLeast(0)} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${score.toInt()}/100",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = getScoreColor(score)
            )
            LinearProgressIndicator(
                progress = { (score.toFloat() / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .width(80.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = getScoreColor(score),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

// Score calculation functions (matching TripScorer logic)
private fun calculateSpeedScore(minor: Int, mid: Int, major: Int, distanceKm: Double): Double {
    val norm = (distanceKm / 10.0).coerceAtLeast(0.3)
    val penalty = 1.0 * (minor / norm) + 2.0 * (mid / norm) + 3.0 * (major / norm)
    return (100.0 - penalty).coerceIn(0.0, 100.0)
}

private fun calculateBrakingScore(minor: Int, mid: Int, major: Int): Double {
    // Braking penalties: absolute values (not normalized)
    // Minor = 25 points, Mid = 50 points, Major = 75 points
    val penalty = 25.0 * minor + 50.0 * mid + 75.0 * major
    return (100.0 - penalty).coerceIn(0.0, 100.0)
}

private fun calculateAccelScore(minor: Int, mid: Int, major: Int, distanceKm: Double): Double {
    val norm = (distanceKm / 10.0).coerceAtLeast(0.3)
    val penalty = 1.0 * (minor / norm) + 2.0 * (mid / norm) + 2.5 * (major / norm)
    return (100.0 - penalty).coerceIn(0.0, 100.0)
}

private fun calculateCorneringScore(minor: Int, mid: Int, major: Int, distanceKm: Double): Double {
    val norm = (distanceKm / 10.0).coerceAtLeast(0.3)
    val penalty = 1.0 * (minor / norm) + 2.0 * (mid / norm) + 2.5 * (major / norm)
    return (100.0 - penalty).coerceIn(0.0, 100.0)
}

private fun calculateDurationScore(durationMin: Double): Double {
    // Duration penalty: max(0, (durationMin - 90) / 30) * 1.0
    val penalty = kotlin.math.max(0.0, (durationMin - 90.0) / 30.0) * 1.0
    return (100.0 - penalty).coerceIn(0.0, 100.0)
}

private fun calculateDistractionScore(handledSeconds: Double, screenOnMovingSeconds: Double, distanceKm: Double): Double {
    // Distraction only counts if there are actual touches (handledSeconds already filtered)
    // Screen on alone is not penalized
    val penalty = 5.0 * (handledSeconds / 60.0)
    return (100.0 - penalty).coerceIn(0.0, 100.0)
}

@Composable
private fun getScoreColor(score: Double): Color {
    return when {
        score >= 80 -> Color(0xFF4CAF50) // Green
        score >= 60 -> Color(0xFFFFC107) // Amber
        score >= 40 -> Color(0xFFFF9800) // Orange
        else -> Color(0xFFF44336) // Red
    }
}

private fun formatDate(epochMs: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMs))
}
