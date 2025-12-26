package com.goofyapps.tripscore.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: RouteEntity)

    @Query("SELECT * FROM routes WHERE routeId = :id LIMIT 1")
    suspend fun get(id: String): RouteEntity?
}
