package com.tripscore.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tripscore.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import com.tripscore.app.data.LocationPointEntity
import com.tripscore.app.data.EventMarkerEntity

class TripDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    fun trip(id: Long) = db.tripDao().observeTrip(id)
    fun locationPoints(id: Long): Flow<List<LocationPointEntity>> = db.locationPointDao().observePoints(id)
    suspend fun getLocationPoints(id: Long): List<LocationPointEntity> = db.locationPointDao().getPoints(id)
    suspend fun getEventMarkers(id: Long): List<EventMarkerEntity> = db.eventMarkerDao().getMarkers(id)
}
