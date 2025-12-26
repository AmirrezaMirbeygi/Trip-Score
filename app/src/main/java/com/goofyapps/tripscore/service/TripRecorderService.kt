package com.goofyapps.tripscore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.google.android.gms.location.*
import android.location.Location
import com.goofyapps.tripscore.MainActivity
import com.goofyapps.tripscore.R
import com.goofyapps.tripscore.data.*
import com.goofyapps.tripscore.logic.RouteFingerprint
import com.goofyapps.tripscore.logic.TripScorer
import com.goofyapps.tripscore.logic.TripStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.Manifest
import android.util.Log

class TripRecorderService : Service() {

    companion object {
        const val ACTION_START = "com.goofyapps.tripscore.action.START"
        const val ACTION_STOP = "com.goofyapps.tripscore.action.STOP"
        const val ACTION_START_TEST = "com.goofyapps.tripscore.action.START_TEST"
        const val ACTION_START_TEST_HARD_BRAKE = "com.goofyapps.tripscore.action.START_TEST_HARD_BRAKE"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "trip_score_recorder"
        private const val PREFS_NAME = "trip_service_prefs"
        private const val KEY_IS_STOPPED = "is_explicitly_stopped"
        private const val KEY_TRIP_ACTIVE = "trip_active"
        private const val KEY_TRIP_START_MS = "trip_start_ms"
        private const val MAX_LOCATION_POINTS = 10000 // Limit to prevent memory issues
        private const val MAX_EVENT_MARKERS = 1000
        
        @Volatile
        private var instance: TripRecorderService? = null

        fun getInstance(): TripRecorderService? = instance
        
        const val ACTION_TOUCH_EVENT = "com.goofyapps.tripscore.action.TOUCH_EVENT"
        
        private fun getPrefs(ctx: Context): SharedPreferences {
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        
        fun isExplicitlyStopped(ctx: Context): Boolean {
            return getPrefs(ctx).getBoolean(KEY_IS_STOPPED, false)
        }
        
        fun setExplicitlyStopped(ctx: Context, stopped: Boolean) {
            getPrefs(ctx).edit().putBoolean(KEY_IS_STOPPED, stopped).apply()
        }
        
        fun isTripActive(ctx: Context): Boolean {
            return getPrefs(ctx).getBoolean(KEY_TRIP_ACTIVE, false)
        }
        
        fun getTripStartMs(ctx: Context): Long {
            return getPrefs(ctx).getLong(KEY_TRIP_START_MS, 0L)
        }
        
        fun setTripActive(ctx: Context, active: Boolean, startMs: Long = 0L) {
            getPrefs(ctx).edit()
                .putBoolean(KEY_TRIP_ACTIVE, active)
                .putLong(KEY_TRIP_START_MS, startMs)
                .apply()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var fused: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null

    private val state = TripStateMachine()
    private val scorer = TripScorer()
    private val route = RouteFingerprint()

    private var currentTripStartMs: Long = 0L
    // Store full location data: lat, lon, timestamp, speed, bearing
    private val locationPoints = mutableListOf<LocationData>()
    // Store event data: lat, lon, timestamp, eventType, value
    private val eventMarkers = mutableListOf<EventData>()
    private var lastLocationTime: Long = 0L
    
    // Test mode state
    private var isTestMode = false
    private var testStartTime = 0L
    private var testLocationJob: kotlinx.coroutines.Job? = null
    private var testHardBrake = false  // Flag for hard braking test scenario

    private val _currentTripState = MutableStateFlow(CurrentTripState())
    val currentTripState: StateFlow<CurrentTripState> = _currentTripState.asStateFlow()
    
    // Expose location points for live map updates
    private val _liveLocationPoints = MutableStateFlow<List<LocationData>>(emptyList())
    val liveLocationPoints: StateFlow<List<LocationData>> = _liveLocationPoints.asStateFlow()
    
    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val speed: Float,
        val bearing: Float
    )
    
    private data class EventData(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val eventType: String,
        val value: Float
    )

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!isTestMode) {
                for (loc in result.locations) {
                    processLocation(loc)
                }
            }
        }
    }
    
    // Process location update (called from both real GPS and test mode)
    private fun processLocation(loc: Location) {
        val update = state.onLocation(loc)
        scorer.onLocation(loc)

        if (update.tripStarted) {
            scorer.startTrip(update.tripStartEpochMs)
            route.start()
            currentTripStartMs = update.tripStartEpochMs
            locationPoints.clear()
            eventMarkers.clear()
            lastLocationTime = 0L
            lastEventCounts = TripScorer.EventCounts(
                minorSpeeding = 0,
                midSpeeding = 0,
                majorSpeeding = 0,
                minorAccel = 0,
                midAccel = 0,
                majorAccel = 0,
                minorBrakes = 0,
                midBrakes = 0,
                majorBrakes = 0,
                minorTurns = 0,
                midTurns = 0,
                majorTurns = 0,
                handledSeconds = 0.0
            )
            _liveLocationPoints.value = emptyList()
            // Latch trip state - persist so it survives service restarts
            setTripActive(applicationContext, true, update.tripStartEpochMs)
            // Immediately update CurrentTripState to trigger UI update
            updateCurrentTripState(update.tripStartEpochMs)
        }
        if (update.tripOngoing) {
            route.onLocation(loc)
            scorer.onPhoneContext(
                ctx = applicationContext,
                speedMps = loc.speed.toDouble()
            )

            // Track location point (sample every 5 seconds to reduce storage)
            // Limit size to prevent memory issues
            if (locationPoints.size < MAX_LOCATION_POINTS && 
                (locationPoints.isEmpty() || (loc.time - lastLocationTime) > 5000)) {
                val newPoint = LocationData(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    timestamp = loc.time,
                    speed = loc.speed,
                    bearing = loc.bearing
                )
                locationPoints.add(newPoint)
                lastLocationTime = loc.time
                // Update live location points for map
                _liveLocationPoints.value = locationPoints.toList()
            }

            // Track all event types
            trackEvents(loc)
            // Always use currentTripStartMs if available, otherwise use the update's start time
            val startTimeToUse = if (currentTripStartMs > 0) currentTripStartMs else update.tripStartEpochMs
            updateCurrentTripState(startTimeToUse)
            // Ensure trip state remains latched
            if (currentTripStartMs > 0) {
                setTripActive(applicationContext, true, currentTripStartMs)
            }
        }
        if (update.tripEnded) {
            // Save copies of data before clearing
            val savedLocationPoints = locationPoints.toList()
            val savedEventMarkers = eventMarkers.toList()
            
            // Immediately clear trip state - trip has ended
            currentTripStartMs = 0L
            locationPoints.clear()
            eventMarkers.clear()
            // Update StateFlow on main thread to ensure UI updates immediately
            val clearedState = CurrentTripState(isActive = false)
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                _currentTripState.value = clearedState
                _liveLocationPoints.value = emptyList()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    _currentTripState.value = clearedState
                    _liveLocationPoints.value = emptyList()
                }
            }
            // Unlatch trip state - trip has ended
            setTripActive(applicationContext, false, 0L)
            
            val tripSummary = scorer.finishTrip(update.tripEndEpochMs, route.finish())
            scope.launch {
                try {
                    val db = AppDatabase.get(applicationContext)
                    val tripId = db.tripDao().insert(tripSummary.tripEntity)

                    // Save location points with actual data
                    if (savedLocationPoints.isNotEmpty()) {
                        val points = savedLocationPoints.map { data ->
                            LocationPointEntity(
                                tripId = tripId,
                                latitude = data.latitude,
                                longitude = data.longitude,
                                timestamp = data.timestamp,
                                speed = data.speed,
                                bearing = data.bearing
                            )
                        }
                        db.locationPointDao().insertAll(points)
                    }

                    // Save event markers with correct timestamps
                    if (savedEventMarkers.isNotEmpty()) {
                        val markers = savedEventMarkers.map { data ->
                            EventMarkerEntity(
                                tripId = tripId,
                                latitude = data.latitude,
                                longitude = data.longitude,
                                timestamp = data.timestamp,
                                eventType = data.eventType,
                                value = data.value
                            )
                        }
                        db.eventMarkerDao().insertAll(markers)
                    }

                    // Fix route aggregation
                    val existingRoute = db.routeDao().get(tripSummary.routeEntity.routeId)
                    val updatedRoute = if (existingRoute != null) {
                        RouteEntity(
                            routeId = tripSummary.routeEntity.routeId,
                            firstSeenEpochMs = existingRoute.firstSeenEpochMs,
                            lastSeenEpochMs = System.currentTimeMillis(),
                            tripCount = existingRoute.tripCount + 1,
                            avgScoreStars = ((existingRoute.avgScoreStars * existingRoute.tripCount) + tripSummary.tripEntity.scoreStars) / (existingRoute.tripCount + 1)
                        )
                    } else {
                        tripSummary.routeEntity
                    }
                    db.routeDao().upsert(updatedRoute)
                } catch (e: Exception) {
                    Log.e("TripRecorderService", "Error saving trip data", e)
                }
            }
        }
    }
    
    private var lastEventCounts = TripScorer.EventCounts(
        minorSpeeding = 0,
        midSpeeding = 0,
        majorSpeeding = 0,
        minorAccel = 0,
        midAccel = 0,
        majorAccel = 0,
        minorBrakes = 0,
        midBrakes = 0,
        majorBrakes = 0,
        minorTurns = 0,
        midTurns = 0,
        majorTurns = 0,
        handledSeconds = 0.0
    )
    
    private fun trackEvents(loc: Location) {
        if (eventMarkers.size >= MAX_EVENT_MARKERS) return
        
        val currentEvents = scorer.getCurrentEvents()
        val v = loc.speed.toDouble().coerceAtLeast(0.0)
        
        // Track speeding events (only when count increases)
        if (currentEvents.majorSpeeding > lastEventCounts.majorSpeeding) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "speeding_major",
                value = v.toFloat()
            ))
        } else if (currentEvents.midSpeeding > lastEventCounts.midSpeeding) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "speeding_mid",
                value = v.toFloat()
            ))
        } else if (currentEvents.minorSpeeding > lastEventCounts.minorSpeeding) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "speeding_minor",
                value = v.toFloat()
            ))
        }
        
        // Track braking events
        if (currentEvents.majorBrakes > lastEventCounts.majorBrakes) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "brake_major",
                value = 0f
            ))
        } else if (currentEvents.midBrakes > lastEventCounts.midBrakes) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "brake_mid",
                value = 0f
            ))
        } else if (currentEvents.minorBrakes > lastEventCounts.minorBrakes) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "brake_minor",
                value = 0f
            ))
        }
        
        // Track acceleration events
        if (currentEvents.majorAccel > lastEventCounts.majorAccel) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "accel_major",
                value = 0f
            ))
        } else if (currentEvents.midAccel > lastEventCounts.midAccel) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "accel_mid",
                value = 0f
            ))
        } else if (currentEvents.minorAccel > lastEventCounts.minorAccel) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "accel_minor",
                value = 0f
            ))
        }
        
        // Track cornering events
        if (currentEvents.majorTurns > lastEventCounts.majorTurns) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "turn_major",
                value = 0f
            ))
        } else if (currentEvents.midTurns > lastEventCounts.midTurns) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "turn_mid",
                value = 0f
            ))
        } else if (currentEvents.minorTurns > lastEventCounts.minorTurns) {
            eventMarkers.add(EventData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = loc.time,
                eventType = "turn_minor",
                value = 0f
            ))
        }
        
        lastEventCounts = currentEvents
    }

    private fun updateCurrentTripState(startEpochMs: Long) {
        // Validate startEpochMs is reasonable (not in the future, not too old)
        val now = System.currentTimeMillis()
        val maxTripDurationMs = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
        
        // If startEpochMs is invalid, clear the trip state
        if (startEpochMs <= 0 || startEpochMs > now || (now - startEpochMs) > maxTripDurationMs) {
            // Invalid start time - clear trip state
            currentTripStartMs = 0L
            setTripActive(applicationContext, false, 0L)
            _currentTripState.value = CurrentTripState(isActive = false)
            return
        }
        
        val durationMin = (now - startEpochMs) / 60000.0
        
        // Get distance from scorer (more accurate than calculating from location points)
        // Scorer tracks distance from actual location updates
        val distanceKm = scorer.getCurrentDistance() / 1000.0

        // Get current events from scorer
        val currentEvents = scorer.getCurrentEvents()

        // Calculate current score using same logic as TripScorer
        val currentScore = calculateCurrentScore(
            durationMin = durationMin,
            distanceKm = distanceKm,
            events = currentEvents
        )
        
        // Update StateFlow - ensure it's on main thread for UI updates
        val newState = CurrentTripState(
            isActive = true,
            startEpochMs = startEpochMs,
            distanceKm = distanceKm,
            durationMin = durationMin,
            currentScore = currentScore,
            minorSpeeding = currentEvents.minorSpeeding,
            midSpeeding = currentEvents.midSpeeding,
            majorSpeeding = currentEvents.majorSpeeding,
            minorBrakes = currentEvents.minorBrakes,
            midBrakes = currentEvents.midBrakes,
            majorBrakes = currentEvents.majorBrakes,
            minorAccel = currentEvents.minorAccel,
            midAccel = currentEvents.midAccel,
            majorAccel = currentEvents.majorAccel,
            minorTurns = currentEvents.minorTurns,
            midTurns = currentEvents.midTurns,
            majorTurns = currentEvents.majorTurns,
            handledSeconds = currentEvents.handledSeconds
        )
        
        // Update on main thread to ensure UI sees the change immediately
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            _currentTripState.value = newState
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                _currentTripState.value = newState
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        fused = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        
        // Restore trip state if service was restarted during an active trip
        // Only restore if the saved start time is recent (within last 24 hours)
        // This prevents using stale timestamps from crashed sessions
        if (isTripActive(applicationContext)) {
            val savedStartMs = getTripStartMs(applicationContext)
            val now = System.currentTimeMillis()
            val maxTripDurationMs = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
            
            if (savedStartMs > 0 && (now - savedStartMs) < maxTripDurationMs) {
                // Only restore if the trip start time is recent (within 24 hours)
                currentTripStartMs = savedStartMs
                scorer.startTrip(savedStartMs)
                route.start()
                // Restore CurrentTripState to show trip is active
                updateCurrentTripState(savedStartMs)
            } else {
                // Stale trip state - clear it
                setTripActive(applicationContext, false, 0L)
                currentTripStartMs = 0L
            }
        }
    }

    fun recordTouchEvent() {
        scorer.onTouchEvent()
    }
    
    fun endTripManually() {
        if (currentTripStartMs > 0) {
            val endMs = System.currentTimeMillis()
            // Save copies of data before clearing
            val savedLocationPoints = locationPoints.toList()
            val savedEventMarkers = eventMarkers.toList()
            
            // Immediately clear trip state - trip has ended
            currentTripStartMs = 0L
            locationPoints.clear()
            eventMarkers.clear()
            // Update StateFlow on main thread to ensure UI updates immediately
            val clearedState = CurrentTripState(isActive = false)
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                _currentTripState.value = clearedState
                _liveLocationPoints.value = emptyList()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    _currentTripState.value = clearedState
                    _liveLocationPoints.value = emptyList()
                }
            }
            // Unlatch trip state - trip has ended
            setTripActive(applicationContext, false, 0L)
            
            val tripSummary = scorer.finishTrip(endMs, route.finish())
            scope.launch {
                try {
                    val db = AppDatabase.get(applicationContext)
                    val tripId = db.tripDao().insert(tripSummary.tripEntity)

                    // Save location points with actual data
                    if (savedLocationPoints.isNotEmpty()) {
                        val points = savedLocationPoints.map { data ->
                            LocationPointEntity(
                                tripId = tripId,
                                latitude = data.latitude,
                                longitude = data.longitude,
                                timestamp = data.timestamp,
                                speed = data.speed,
                                bearing = data.bearing
                            )
                        }
                        db.locationPointDao().insertAll(points)
                    }

                    // Save event markers with correct timestamps
                    if (savedEventMarkers.isNotEmpty()) {
                        val markers = savedEventMarkers.map { data ->
                            EventMarkerEntity(
                                tripId = tripId,
                                latitude = data.latitude,
                                longitude = data.longitude,
                                timestamp = data.timestamp,
                                eventType = data.eventType,
                                value = data.value
                            )
                        }
                        db.eventMarkerDao().insertAll(markers)
                    }

                    // Fix route aggregation
                    val existingRoute = db.routeDao().get(tripSummary.routeEntity.routeId)
                    val updatedRoute = if (existingRoute != null) {
                        RouteEntity(
                            routeId = tripSummary.routeEntity.routeId,
                            firstSeenEpochMs = existingRoute.firstSeenEpochMs,
                            lastSeenEpochMs = System.currentTimeMillis(),
                            tripCount = existingRoute.tripCount + 1,
                            avgScoreStars = ((existingRoute.avgScoreStars * existingRoute.tripCount) + tripSummary.tripEntity.scoreStars) / (existingRoute.tripCount + 1)
                        )
                    } else {
                        tripSummary.routeEntity
                    }
                    db.routeDao().upsert(updatedRoute)
                } catch (e: Exception) {
                    Log.e("TripRecorderService", "Error saving trip data", e)
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                setExplicitlyStopped(this, false)
                startRecording()
            }
            ACTION_START_TEST -> {
                setExplicitlyStopped(this, false)
                startTestMode(hardBrake = false)
            }
            ACTION_START_TEST_HARD_BRAKE -> {
                setExplicitlyStopped(this, false)
                startTestMode(hardBrake = true)
            }
            ACTION_STOP -> {
                setExplicitlyStopped(this, true)
                if (isTestMode) {
                    stopTestMode()
                } else {
                    stopRecording()
                }
            }
            null -> {
                // Auto-start if service was restarted by system (unless explicitly stopped)
                if (!isExplicitlyStopped(this)) {
                    startRecording()
                }
            }
        }
        // Only restart if recording is active (not explicitly stopped)
        return if (isExplicitlyStopped(this)) {
            START_NOT_STICKY // Don't restart if explicitly stopped
        } else {
            START_STICKY // Restart if recording is active
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Only restart service if recording is active (not explicitly stopped)
        // This ensures recording continues even when app is closed, but only if user started recording
        if (!isExplicitlyStopped(this)) {
            val restartIntent = Intent(applicationContext, TripRecorderService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(applicationContext, restartIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        if (!isTestMode) {
            fused.removeLocationUpdates(locationCallback)
        }
        releaseWakeLock()
        instance = null
        
        // If explicitly stopped, don't restart
        // If not explicitly stopped but service was killed, START_STICKY will restart it
    }

    private fun startRecording() {
        acquireWakeLock()
        startForeground(NOTIF_ID, buildNotification("Trip detection running"))

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        try {
            fused.requestLocationUpdates(req, locationCallback, mainLooper)
            
            // Always run periodic updates to maintain UI state
            // This ensures the UI stays updated even if location updates are delayed
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(2000) // Update every 2 seconds
                    if (currentTripStartMs > 0) {
                        updateCurrentTripState(currentTripStartMs)
                    }
                    // Continue running even if no trip - will update when trip starts
                }
            }
        } catch (e: SecurityException) {
            Log.e("TripRecorderService", "Location permission denied", e)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isTestMode) {
            fused.removeLocationUpdates(locationCallback)
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        instance = null
        stopSelf()
    }
    
    fun startTestTrip() {
        val intent = Intent(this, TripRecorderService::class.java).apply {
            action = ACTION_START_TEST
        }
        ContextCompat.startForegroundService(this, intent)
    }
    
    fun startTestTripHardBrake() {
        val intent = Intent(this, TripRecorderService::class.java).apply {
            action = ACTION_START_TEST_HARD_BRAKE
        }
        ContextCompat.startForegroundService(this, intent)
    }
    
    /**
     * Generate test location data based on elapsed time:
     * Normal test:
     * - 0-5s: stationary (0 m/s)
     * - 5-125s: driving at 10 m/s (2 minutes)
     * - 125-185s: accelerate to 20 m/s, then drive at 20 m/s (1 minute)
     * - 185-205s: brake to 0 m/s (20 seconds) - smooth braking
     * - 205-805s: stationary (10 minutes)
     * 
     * Hard brake test:
     * - 0-5s: stationary (0 m/s)
     * - 5-125s: driving at 10 m/s (2 minutes)
     * - 125-185s: accelerate to 20 m/s, then drive at 20 m/s (1 minute)
     * - 185-190s: hard brake to 0 m/s (5 seconds) - triggers braking events
     * - 190-805s: stationary (10+ minutes)
     */
    private fun generateTestLocation(elapsedSeconds: Double): Location {
        val location = Location("test")
        val baseLat = 37.7749  // San Francisco coordinates (can be any location)
        val baseLon = -122.4194
        val bearing = 45.0  // Northeast direction
        
        val speed: Double
        val distance: Double
        
        when {
            elapsedSeconds < 5.0 -> {
                // Stationary for 5 seconds
                speed = 0.0
                distance = 0.0
            }
            elapsedSeconds < 125.0 -> {
                // Drive at 10 m/s for 2 minutes (120 seconds)
                val driveTime = elapsedSeconds - 5.0
                speed = 10.0
                distance = driveTime * 10.0  // meters
            }
            elapsedSeconds < 185.0 -> {
                // Accelerate to 20 m/s over ~5 seconds, then drive at 20 m/s
                val phaseTime = elapsedSeconds - 125.0
                if (phaseTime < 5.0) {
                    // Acceleration phase: linear from 10 to 20 m/s over 5 seconds
                    speed = 10.0 + (phaseTime / 5.0) * 10.0
                    // Average speed during acceleration
                    distance = 120.0 * 10.0 + (phaseTime * (10.0 + speed) / 2.0)
                } else {
                    // Constant speed at 20 m/s
                    val constantTime = phaseTime - 5.0
                    speed = 20.0
                    distance = 120.0 * 10.0 + (5.0 * 15.0) + (constantTime * 20.0)
                }
            }
            elapsedSeconds < (if (testHardBrake) 188.0 else 205.0) -> {
                // Braking phase
                val brakeTime = elapsedSeconds - 185.0
                if (testHardBrake) {
                    // Hard brake: decelerate from 20 to 0 m/s over 3 seconds
                    // This gives -6.67 m/s² deceleration, which triggers MAJOR braking (< -4.5 m/s²)
                    speed = 20.0 * (1.0 - brakeTime / 3.0)
                    // Distance during hard braking (average speed * time)
                    val avgSpeedDuringBrake = (20.0 + speed) / 2.0
                    distance = 120.0 * 10.0 + (5.0 * 15.0) + (55.0 * 20.0) + (brakeTime * avgSpeedDuringBrake)
                } else {
                    // Smooth brake: decelerate from 20 to 0 m/s over 20 seconds
                    // This gives -1.0 m/s² deceleration, below threshold
                    speed = 20.0 * (1.0 - brakeTime / 20.0)
                    // Distance during braking (average speed * time)
                    val avgSpeedDuringBrake = (20.0 + speed) / 2.0
                    distance = 120.0 * 10.0 + (5.0 * 15.0) + (55.0 * 20.0) + (brakeTime * avgSpeedDuringBrake)
                }
            }
            else -> {
                // Stationary for remaining time
                val brakeDistance = if (testHardBrake) {
                    // Hard brake: 3 seconds at average 10 m/s = 30m
                    30.0
                } else {
                    // Smooth brake: 20 seconds at average 10 m/s = 200m
                    200.0
                }
                speed = 0.0
                distance = 120.0 * 10.0 + (5.0 * 15.0) + (55.0 * 20.0) + brakeDistance
            }
        }
        
        // Calculate position based on distance and bearing
        val distanceKm = distance / 1000.0
        val latOffset = distanceKm * Math.cos(Math.toRadians(bearing)) / 111.0  // ~111 km per degree latitude
        val lonOffset = distanceKm * Math.sin(Math.toRadians(bearing)) / (111.0 * Math.cos(Math.toRadians(baseLat)))
        
        location.latitude = baseLat + latOffset
        location.longitude = baseLon + lonOffset
        location.speed = speed.toFloat()
        location.bearing = bearing.toFloat()
        location.time = testStartTime + (elapsedSeconds * 1000).toLong()
        location.accuracy = 5.0f  // Simulate GPS accuracy
        
        return location
    }
    
    private fun startTestMode(hardBrake: Boolean = false) {
        isTestMode = true
        testHardBrake = hardBrake
        testStartTime = System.currentTimeMillis()
        val testName = if (hardBrake) "Test mode - Hard Braking" else "Test mode - Simulating trip"
        acquireWakeLock()
        startForeground(NOTIF_ID, buildNotification(testName))
        
        // Don't start trip immediately - let TripStateMachine detect it naturally
        // when speed exceeds threshold (after 5 seconds when car starts moving)
        
        // Generate test location updates every 1 second
        val totalDuration = if (hardBrake) 788.0 else 805.0  // Hard brake test is shorter (3s brake vs 20s)
        testLocationJob = scope.launch {
            var elapsedSeconds = 0.0
            while (isTestMode && elapsedSeconds < totalDuration) {
                val location = generateTestLocation(elapsedSeconds)
                processLocation(location)
                
                elapsedSeconds += 1.0
                kotlinx.coroutines.delay(1000)  // 1 second between updates
            }
            
            // Test trip ended - wait at 0 speed, then auto-end
            if (isTestMode && elapsedSeconds >= totalDuration) {
                // Trip should have ended naturally by now (5 minutes at low speed)
                // But if not, we can manually end it
                kotlinx.coroutines.delay(5000)  // Wait a bit more
                if (currentTripStartMs > 0) {
                    endTripManually()
                }
            }
        }
        
        // Periodic UI updates
        scope.launch {
            while (isTestMode && currentTripStartMs > 0) {
                kotlinx.coroutines.delay(2000)
                updateCurrentTripState(currentTripStartMs)
            }
        }
    }
    
    private fun stopTestMode() {
        isTestMode = false
        testLocationJob?.cancel()
        testLocationJob = null
        stopRecording()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TripScore:TripWakelock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L) // 10 hours timeout - should be more than enough for any trip
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Trip Score")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun calculateCurrentScore(
        durationMin: Double,
        distanceKm: Double,
        events: TripScorer.EventCounts
    ): Double {
        // For very short trips or trips with no distance yet, use a minimum norm to prevent division issues
        val norm = if (distanceKm > 0.1) {
            (distanceKm / 10.0).coerceAtLeast(0.3)
        } else {
            0.3 // Use minimum norm for trips that just started
        }

        // Speed: minor = 1.0x, mid = 2.0x, major = 3.0x
        val spdPenalty = 1.0 * (events.minorSpeeding / norm) + 2.0 * (events.midSpeeding / norm) + 3.0 * (events.majorSpeeding / norm)
        
        // Acceleration: minor = 1.0x, mid = 2.0x, major = 2.5x
        val accPenalty = 1.0 * (events.minorAccel / norm) + 2.0 * (events.midAccel / norm) + 2.5 * (events.majorAccel / norm)
        
        // Braking penalties: absolute values (not normalized)
        // Reduced penalties: Minor = 15 points, Mid = 30 points, Major = 45 points
        // This makes 3 minor + 1 mid = 75 penalty → 25/100 score (instead of 0/100)
        val brkPenalty = 15.0 * events.minorBrakes + 30.0 * events.midBrakes + 45.0 * events.majorBrakes
        
        // Cornering: minor = 1.0x, mid = 2.0x, major = 2.5x
        val corPenalty = 1.0 * (events.minorTurns / norm) + 2.0 * (events.midTurns / norm) + 2.5 * (events.majorTurns / norm)

        val durPenalty = kotlin.math.max(0.0, (durationMin - 90.0) / 30.0) * 1.0

        // Distraction penalty
        val disPenalty = 5.0 * (events.handledSeconds / 60.0)

        val raw = spdPenalty + accPenalty + brkPenalty + corPenalty + durPenalty + disPenalty
        // Note: nightFactor not applied during trip (only at end)
        
        return kotlin.math.min(100.0, kotlin.math.max(0.0, 100.0 - raw))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            nm.createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
