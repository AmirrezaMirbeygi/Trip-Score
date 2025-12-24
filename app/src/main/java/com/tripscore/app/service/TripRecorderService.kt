package com.tripscore.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
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
import kotlin.math.abs

class TripRecorderService : Service() {

    companion object {
        const val ACTION_START = "com.tripscore.app.action.START"
        const val ACTION_STOP = "com.tripscore.app.action.STOP"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "trip_score_recorder"
        
        @Volatile
        private var instance: TripRecorderService? = null

        fun getInstance(): TripRecorderService? = instance
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var fused: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null

    private val state = TripStateMachine()
    private val scorer = TripScorer()
    private val route = RouteFingerprint()

    private var currentTripStartMs: Long = 0L
    private val locationPoints = mutableListOf<Pair<Double, Double>>() // lat, lon
    private val eventMarkers = mutableListOf<Triple<Double, Double, String>>() // lat, lon, eventType
    private var lastLocationTime: Long = 0L

    private val _currentTripState = MutableStateFlow(CurrentTripState())
    val currentTripState: StateFlow<CurrentTripState> = _currentTripState.asStateFlow()

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
            }
            if (update.tripOngoing) {
                route.onLocation(loc)
                scorer.onPhoneContext(
                    ctx = applicationContext,
                    speedMps = loc.speed.toDouble()
                )

                // Track location point (sample every 5 seconds to reduce storage)
                if (locationPoints.isEmpty() || (loc.time - lastLocationTime) > 5000) {
                    locationPoints.add(Pair(loc.latitude, loc.longitude))
                    lastLocationTime = loc.time
                }

                // Check for events and create markers
                val v = loc.speed.toDouble().coerceAtLeast(0.0)
                val DEFAULT_LIMIT_MPS = 27.78
                if (v > DEFAULT_LIMIT_MPS * 1.20) {
                    eventMarkers.add(Triple(loc.latitude, loc.longitude, "speeding_major"))
                } else if (v > DEFAULT_LIMIT_MPS * 1.10) {
                    eventMarkers.add(Triple(loc.latitude, loc.longitude, "speeding_minor"))
                }

                updateCurrentTripState(update.tripStartEpochMs)
            }
            if (update.tripEnded) {
                val tripSummary = scorer.finishTrip(update.tripEndEpochMs, route.finish())
                scope.launch {
                    val db = AppDatabase.get(applicationContext)
                    val tripId = db.tripDao().insert(tripSummary.tripEntity)

                    // Save location points
                    if (locationPoints.isNotEmpty()) {
                        val points = locationPoints.mapIndexed { index, (lat, lon) ->
                            LocationPointEntity(
                                tripId = tripId,
                                latitude = lat,
                                longitude = lon,
                                timestamp = currentTripStartMs + (index * 5000L), // Approximate
                                speed = 0f, // Would need to store
                                bearing = 0f
                            )
                        }
                        db.locationPointDao().insertAll(points)
                    }

                    // Save event markers
                    if (eventMarkers.isNotEmpty()) {
                        val markers = eventMarkers.map { (lat, lon, eventType) ->
                            EventMarkerEntity(
                                tripId = tripId,
                                latitude = lat,
                                longitude = lon,
                                timestamp = System.currentTimeMillis(), // Approximate
                                eventType = eventType,
                                value = 0f
                            )
                        }
                        db.eventMarkerDao().insertAll(markers)
                    }

                    db.routeDao().upsert(tripSummary.routeEntity)
                }
                currentTripStartMs = 0L
                locationPoints.clear()
                eventMarkers.clear()
                _currentTripState.value = CurrentTripState()
            }
        }
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
                prev.first, prev.second,
                curr.first, curr.second,
                results
            )
            distanceM += results[0]
        }
        val distanceKm = distanceM / 1000.0

        _currentTripState.value = CurrentTripState(
            isActive = true,
            startEpochMs = startEpochMs,
            distanceKm = distanceKm,
            durationMin = durationMin,
            currentScore = 100.0, // Simplified - would calculate from current metrics
            minorSpeeding = eventMarkers.count { it.third == "speeding_minor" },
            majorSpeeding = eventMarkers.count { it.third == "speeding_major" },
            hardBrakes = 0, // Would need to track from scorer
            panicBrakes = 0,
            moderateAccel = 0,
            aggressiveAccel = 0,
            sharpTurns = 0,
            aggressiveTurns = 0,
            handledSeconds = 0.0
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        fused = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
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
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopRecording() {
        fused.removeLocationUpdates(locationCallback)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        val intent = Intent(this, MainActivity::class.java)
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
