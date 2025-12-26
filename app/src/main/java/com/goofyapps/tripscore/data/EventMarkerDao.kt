package com.goofyapps.tripscore.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventMarkerDao {
    @Insert
    suspend fun insert(marker: EventMarkerEntity): Long

    @Insert
    suspend fun insertAll(markers: List<EventMarkerEntity>)

    @Query("SELECT * FROM event_markers WHERE tripId = :tripId ORDER BY timestamp ASC")
    fun observeMarkers(tripId: Long): Flow<List<EventMarkerEntity>>

    @Query("SELECT * FROM event_markers WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getMarkers(tripId: Long): List<EventMarkerEntity>
}

