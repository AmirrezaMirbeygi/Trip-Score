package com.tripscore.app.service

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
import com.tripscore.app.MainActivity
import com.tripscore.app.R
import com.tripscore.app.data.*
import com.tripscore.app.logic.RouteFingerprint
import com.tripscore.app.logic.TripScorer
import com.tripscore.app.logic.TripStateMachine
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
        const val ACTION_START = "com.tripscore.app.action.START"
        const val ACTION_STOP = "com.tripscore.app.action.STOP"
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
        
        const val ACTION_TOUCH_EVENT = "com.tripscore.app.action.TOUCH_EVENT"
        
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
            val loc = result.lastLocation ?: return
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
                updateCurrentTripState(update.tripStartEpochMs)
                // Ensure trip state remains latched
                if (currentTripStartMs > 0) {
                    setTripActive(applicationContext, true, currentTripStartMs)
                }
            }
            if (update.tripEnded) {
                // Unlatch trip state - trip has ended
                setTripActive(applicationContext, false, 0L)
                val tripSummary = scorer.finishTrip(update.tripEndEpochMs, route.finish())
                scope.launch {
                    try {
                        val db = AppDatabase.get(applicationContext)
                        val tripId = db.tripDao().insert(tripSummary.tripEntity)

                        // Save location points with actual data
                        if (locationPoints.isNotEmpty()) {
                            val points = locationPoints.map { data ->
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
                        if (eventMarkers.isNotEmpty()) {
                            val markers = eventMarkers.map { data ->
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
                currentTripStartMs = 0L
                locationPoints.clear()
                eventMarkers.clear()
                // Clear trip state immediately - trip has ended (isActive = false)
                _currentTripState.value = CurrentTripState(isActive = false)
                _liveLocationPoints.value = emptyList()
                // Unlatch trip state - trip has ended
                setTripActive(applicationContext, false, 0L)
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
        val DEFAULT_LIMIT_MPS = 27.78
        
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
        val now = System.currentTimeMillis()
        val durationMin = (now - startEpochMs) / 60000.0
        
        // Calculate approximate distance from location points
        var distanceM = 0.0
        for (i in 1 until locationPoints.size) {
            val prev = locationPoints[i - 1]
            val curr = locationPoints[i]
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude,
                results
            )
            distanceM += results[0]
        }
        val distanceKm = distanceM / 1000.0

        // Get current events from scorer
        val currentEvents = scorer.getCurrentEvents()

        _currentTripState.value = CurrentTripState(
            isActive = true,
            startEpochMs = startEpochMs,
            distanceKm = distanceKm,
            durationMin = durationMin,
            currentScore = 100.0, // Would calculate from current metrics
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
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        fused = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        
        // Restore trip state if service was restarted during an active trip
        if (isTripActive(applicationContext)) {
            val savedStartMs = getTripStartMs(applicationContext)
            if (savedStartMs > 0) {
                currentTripStartMs = savedStartMs
                scorer.startTrip(savedStartMs)
                route.start()
                // Restore CurrentTripState to show trip is active
                updateCurrentTripState(savedStartMs)
            }
        }
    }

    fun recordTouchEvent() {
        scorer.onTouchEvent()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                setExplicitlyStopped(this, false)
                startRecording()
            }
            ACTION_STOP -> {
                setExplicitlyStopped(this, true)
                stopRecording()
            }
            null -> {
                // Auto-start if service was restarted by system (unless explicitly stopped)
                if (!isExplicitlyStopped(this)) {
                    startRecording()
                }
            }
        }
        return START_STICKY // Service will be restarted if killed
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Restart service if app is swiped away from recent apps
        // This ensures recording continues even when app is closed
        if (!isExplicitlyStopped(this)) {
            val restartIntent = Intent(applicationContext, TripRecorderService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(applicationContext, restartIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only clean up if explicitly stopped, otherwise system will restart via START_STICKY
        if (isExplicitlyStopped(this)) {
            fused.removeLocationUpdates(locationCallback)
            releaseWakeLock()
            instance = null
        } else {
            // Service was killed by system - it will restart automatically
            // Don't release resources here, let onStartCommand handle restart
        }
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
        fused.removeLocationUpdates(locationCallback)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        instance = null
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TripScore:TripWakelock").apply {
            setReferenceCounted(false)
            acquire()
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
