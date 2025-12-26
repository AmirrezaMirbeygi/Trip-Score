package com.goofyapps.tripscore.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.goofyapps.tripscore.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import com.goofyapps.tripscore.data.LocationPointEntity
import com.goofyapps.tripscore.data.EventMarkerEntity

class TripDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    fun trip(id: Long) = db.tripDao().observeTrip(id)
    fun locationPoints(id: Long): Flow<List<LocationPointEntity>> = db.locationPointDao().observePoints(id)
    suspend fun getLocationPoints(id: Long): List<LocationPointEntity> = db.locationPointDao().getPoints(id)
    suspend fun getEventMarkers(id: Long): List<EventMarkerEntity> = db.eventMarkerDao().getMarkers(id)
}
