package com.tripscore.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "event_markers",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tripId"])]
)
data class EventMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val eventType: String, // "speeding_minor", "speeding_major", "brake_hard", "brake_panic", "accel_moderate", "accel_aggressive", "turn_sharp", "turn_aggressive"
    val value: Float // speed, acceleration, etc.
)

