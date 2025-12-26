package com.goofyapps.tripscore.data

data class CurrentTripState(
    val isActive: Boolean = false,
    val startEpochMs: Long = 0L,
    val distanceKm: Double = 0.0,
    val durationMin: Double = 0.0,
    val currentScore: Double = 100.0,
    val minorSpeeding: Int = 0,
    val midSpeeding: Int = 0,
    val majorSpeeding: Int = 0,
    val minorBrakes: Int = 0,
    val midBrakes: Int = 0,
    val majorBrakes: Int = 0,
    val minorAccel: Int = 0,
    val midAccel: Int = 0,
    val majorAccel: Int = 0,
    val minorTurns: Int = 0,
    val midTurns: Int = 0,
    val majorTurns: Int = 0,
    val handledSeconds: Double = 0.0
)

