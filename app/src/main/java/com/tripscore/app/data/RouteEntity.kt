package com.tripscore.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val routeId: String,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val tripCount: Int,
    val avgScoreStars: Double
)
