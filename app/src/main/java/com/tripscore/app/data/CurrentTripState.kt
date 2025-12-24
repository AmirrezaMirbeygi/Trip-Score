package com.tripscore.app.data

data class CurrentTripState(
    val isActive: Boolean = false,
    val startEpochMs: Long = 0L,
    val distanceKm: Double = 0.0,
    val durationMin: Double = 0.0,
    val currentScore: Double = 100.0,
    val minorSpeeding: Int = 0,
    val majorSpeeding: Int = 0,
    val hardBrakes: Int = 0,
    val panicBrakes: Int = 0,
    val moderateAccel: Int = 0,
    val aggressiveAccel: Int = 0,
    val sharpTurns: Int = 0,
    val aggressiveTurns: Int = 0,
    val handledSeconds: Double = 0.0
)

