package com.goofyapps.tripscore.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goofyapps.tripscore.data.AppDatabase
import kotlinx.coroutines.launch

class TripsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val trips = db.tripDao().observeTrips()

    fun deleteTrip(id: Long) {
        viewModelScope.launch {
            db.tripDao().delete(id)
        }
    }
}
