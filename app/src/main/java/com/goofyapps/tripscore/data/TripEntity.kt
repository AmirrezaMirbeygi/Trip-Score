package com.goofyapps.tripscore.data

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
    val midSpeeding: Int,
    val majorSpeeding: Int,

    val minorAccel: Int,
    val midAccel: Int,
    val majorAccel: Int,

    val minorBrakes: Int,
    val midBrakes: Int,
    val majorBrakes: Int,

    val minorTurns: Int,
    val midTurns: Int,
    val majorTurns: Int,

    val handledSeconds: Double,
    val screenOnMovingSeconds: Double,

    val nightMinutes: Double
)
