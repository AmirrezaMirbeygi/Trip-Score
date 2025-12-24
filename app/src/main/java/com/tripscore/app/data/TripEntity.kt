package com.tripscore.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val durationMin: Double,
    val distanceKm: Double,
    val routeId: String,

    val score100: Double,
    val scoreStars: Int,

    val minorSpeeding: Int,
    val majorSpeeding: Int,

    val moderateAccel: Int,
    val aggressiveAccel: Int,

    val hardBrakes: Int,
    val panicBrakes: Int,

    val sharpTurns: Int,
    val aggressiveTurns: Int,

    val handledSeconds: Double,
    val screenOnMovingSeconds: Double,

    val nightMinutes: Double
)
