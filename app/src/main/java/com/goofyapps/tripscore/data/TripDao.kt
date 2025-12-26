package com.goofyapps.tripscore.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Query("SELECT * FROM trips ORDER BY startEpochMs DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id LIMIT 1")
    fun observeTrip(id: Long): Flow<TripEntity?>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)
}
