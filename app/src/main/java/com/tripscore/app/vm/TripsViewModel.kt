package com.tripscore.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tripscore.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TripsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val trips = db.tripDao().observeTrips()

    fun deleteTrip(id: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            db.tripDao().delete(id)
        }
    }
}
