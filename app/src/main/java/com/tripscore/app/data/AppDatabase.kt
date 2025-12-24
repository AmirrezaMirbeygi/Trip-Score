package com.tripscore.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, RouteEntity::class, LocationPointEntity::class, EventMarkerEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun routeDao(): RouteDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun eventMarkerDao(): EventMarkerDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "trips.db"
                )
                .fallbackToDestructiveMigration() // For development - remove in production
                .build().also { INSTANCE = it }
            }
        }
    }
}
