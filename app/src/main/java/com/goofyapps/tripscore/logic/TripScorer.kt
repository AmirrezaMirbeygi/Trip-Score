package com.goofyapps.tripscore.logic

import android.app.KeyguardManager
import android.content.Context
import android.location.Location
import android.os.PowerManager
import android.util.Log
import com.goofyapps.tripscore.data.RouteEntity
import com.goofyapps.tripscore.data.TripEntity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TripScorer {

    data class Summary(val tripEntity: TripEntity, val routeEntity: RouteEntity)

    private var inTrip = false
    private var tripStartMs = 0L

    private var distanceM = 0.0
    private var lastLoc: Location? = null
    private var lastSpeed = 0.0
    private var lastBearingRad = 0.0
    private var lastBearingRadRaw = 0.0  // Raw bearing for cornering detection (unfiltered)
    private var lastTs = 0L
    
    // Diagnostic: track maximum cornering acceleration
    private var maxCorneringAccel = 0.0
    private var maxCorneringSpeed = 0.0
    private var maxCorneringYawRate = 0.0
    
    // Low-pass filter state for noise reduction
    private var filteredSpeed = 0.0
    private var filteredBearingRad = 0.0
    private var isFirstLocation = true  // Track if this is the first location reading
    private val ALPHA_SPEED = 0.2  // Smoothing factor for speed (0.0-1.0, lower = more smoothing)
    private val ALPHA_BEARING = 0.3  // Smoothing factor for bearing (0.0-1.0, lower = more smoothing)

    private var minorSpeeding = 0
    private var midSpeeding = 0
    private var majorSpeeding = 0
    private var minorAccel = 0
    private var midAccel = 0
    private var majorAccel = 0
    private var minorBrakes = 0
    private var midBrakes = 0
    private var majorBrakes = 0
    private var minorTurns = 0
    private var midTurns = 0
    private var majorTurns = 0

    private var handledSeconds = 0.0
    private var screenOnMovingSeconds = 0.0
    private var nightSeconds = 0.0
    
    // Touch tracking for distraction detection
    private val touchTimestamps = mutableListOf<Long>()
    private val TOUCH_WINDOW_MS = 5000L // 5 second window to count touches
    private val MIN_TOUCHES_FOR_DISTRACTION = 2 // Need at least 2 touches to count as distracted

    // Event grouping: successive events of same type within 10 seconds count as 1 event
    // This prevents counting multiple related events (e.g., multiple brakes in quick succession) as separate events
    private val EVENT_GROUPING_WINDOW_MS = 10000L // 10 second window for event grouping
    private val EVENT_GROUPING_SEVERITY_RESET_MS = 3000L // 3 seconds to allow lower severity events to start new group
    private var lastSpeedingTime = 0L
    private var lastSpeedingSeverity = 0 // 0=none, 1=minor, 2=mid, 3=major
    private var lastAccelTime = 0L
    private var lastAccelSeverity = 0
    private var lastBrakeTime = 0L
    private var lastBrakeSeverity = 0
    private var lastTurnTime = 0L
    private var lastTurnSeverity = 0

    private val MIN_SPEED_FOR_EVENTS = 4.0 // ~14.4 km/h
    private val MIN_DT_S = 0.4

    fun startTrip(startEpochMs: Long) {
        inTrip = true
        tripStartMs = startEpochMs

        distanceM = 0.0
        lastLoc = null
        lastSpeed = 0.0
        lastBearingRad = 0.0
        lastBearingRadRaw = 0.0
        lastTs = 0L
        filteredSpeed = 0.0
        filteredBearingRad = 0.0
        isFirstLocation = true
        maxCorneringAccel = 0.0
        maxCorneringSpeed = 0.0
        maxCorneringYawRate = 0.0

        minorSpeeding = 0
        midSpeeding = 0
        majorSpeeding = 0
        minorAccel = 0
        midAccel = 0
        majorAccel = 0
        minorBrakes = 0
        midBrakes = 0
        majorBrakes = 0
        minorTurns = 0
        midTurns = 0
        majorTurns = 0

        handledSeconds = 0.0
        screenOnMovingSeconds = 0.0
        nightSeconds = 0.0
        touchTimestamps.clear()
        
        // Reset event grouping state
        lastSpeedingTime = 0L
        lastSpeedingSeverity = 0
        lastAccelTime = 0L
        lastAccelSeverity = 0
        lastBrakeTime = 0L
        lastBrakeSeverity = 0
        lastTurnTime = 0L
        lastTurnSeverity = 0
    }

    fun onLocation(loc: Location) {
        if (!inTrip) return
        val prev = lastLoc
        val now = loc.time
        if (prev != null) distanceM += prev.distanceTo(loc).toDouble()

        val dtS = if (lastTs == 0L) 0.0 else max(0.0, (now - lastTs) / 1000.0)
        lastTs = now
        lastLoc = loc

        val vRaw = loc.speed.toDouble().coerceAtLeast(0.0)
        val bearingRadRaw = Math.toRadians(loc.bearing.toDouble())
        
        // Apply low-pass filter to reduce GPS noise
        if (isFirstLocation) {
            // First reading - initialize filter with raw values
            filteredSpeed = vRaw
            filteredBearingRad = bearingRadRaw
            isFirstLocation = false
        } else {
            // Low-pass filter: filtered = alpha * new + (1 - alpha) * filtered_prev
            // This smooths out rapid fluctuations while still responding to real changes
            filteredSpeed = ALPHA_SPEED * vRaw + (1.0 - ALPHA_SPEED) * filteredSpeed
            // For bearing, handle wrap-around (0-360 degrees) properly
            val bearingDiff = wrapAngleRad(bearingRadRaw - filteredBearingRad)
            filteredBearingRad = wrapAngleRad(filteredBearingRad + ALPHA_BEARING * bearingDiff)
        }
        
        // Use filtered values for all calculations
        val v = filteredSpeed
        val bearingRad = filteredBearingRad

        // Fixed speeding thresholds (temporarily set to 200 km/h to disable - will implement speed limit detection later)
        // Minor: > 200 km/h, Mid: > 200 km/h, Major: > 200 km/h
        val vKmh = v * 3.6 // Convert m/s to km/h
        val minorThresholdKmh = 200.0  // TODO: Replace with actual speed limit detection
        val midThresholdKmh = 200.0    // TODO: Replace with actual speed limit detection
        val majorThresholdKmh = 200.0  // TODO: Replace with actual speed limit detection
        
        // Determine speeding severity (0=none, 1=minor, 2=mid, 3=major)
        val speedingSeverity = when {
            vKmh > majorThresholdKmh -> 3
            vKmh > midThresholdKmh -> 2
            vKmh > minorThresholdKmh -> 1
            else -> 0
        }
        
        if (speedingSeverity > 0) {
            handleGroupedEvent(
                now, speedingSeverity,
                ::lastSpeedingTime, ::lastSpeedingSeverity,
                { sev -> when (sev) { 3 -> majorSpeeding++; 2 -> midSpeeding++; 1 -> minorSpeeding++ } },
                { sev -> when (sev) { 3 -> majorSpeeding--; 2 -> midSpeeding--; 1 -> minorSpeeding-- } }
            )
        }

        if (dtS >= MIN_DT_S) {
            // Only calculate acceleration/braking/cornering if we have valid previous values
            // Skip the first update after trip start to avoid false events from initialization
            // Check if this is the first location update (lastSpeed was 0 and we're now moving)
            val isFirstUpdateAfterStart = lastSpeed == 0.0 && v > MIN_SPEED_FOR_EVENTS
            
            if (!isFirstUpdateAfterStart && v > MIN_SPEED_FOR_EVENTS) {
                val aLong = (v - lastSpeed) / dtS
                
                // Acceleration thresholds (more sensitive to catch moderate acceleration)
                // Minor: > 2.5 m/s² (moderate acceleration)
                // Mid: > 3.5 m/s² (aggressive acceleration)
                // Major: > 5.0 m/s² (very aggressive acceleration)
                val accelSeverity = when {
                    aLong > 5.0 -> 3
                    aLong > 3.5 -> 2
                    aLong > 2.5 -> 1
                    else -> 0
                }
                
                if (accelSeverity > 0) {
                    handleGroupedEvent(
                        now, accelSeverity,
                        ::lastAccelTime, ::lastAccelSeverity,
                        { sev -> when (sev) { 3 -> majorAccel++; 2 -> midAccel++; 1 -> minorAccel++ } },
                        { sev -> when (sev) { 3 -> majorAccel--; 2 -> midAccel--; 1 -> minorAccel-- } }
                    )
                }
                
                // Braking: more sensitive thresholds to catch minor bad brakes
                // Minor: < -1.5 m/s² (more sensitive than previous -2.0)
                // Mid: < -2.5 m/s² (more sensitive than previous -3.0)
                // Major: < -3.5 m/s² (more sensitive than previous -4.5)
                val brakeSeverity = when {
                    aLong < -3.5 -> 3
                    aLong < -2.5 -> 2
                    aLong < -1.5 -> 1
                    else -> 0
                }
                
                if (brakeSeverity > 0) {
                    handleGroupedEvent(
                        now, brakeSeverity,
                        ::lastBrakeTime, ::lastBrakeSeverity,
                        { sev -> when (sev) { 3 -> majorBrakes++; 2 -> midBrakes++; 1 -> minorBrakes++ } },
                        { sev -> when (sev) { 3 -> majorBrakes--; 2 -> midBrakes--; 1 -> minorBrakes-- } }
                    )
                }

                // Use raw bearing for cornering detection to capture actual direction changes
                // (filtered bearing smooths out the cornering signal too much)
                val dBear = wrapAngleRad(bearingRadRaw - lastBearingRadRaw)
                val yawRate = dBear / dtS
                val aLat = abs(v * yawRate)
                
                // Diagnostic: track maximum cornering values
                if (aLat > maxCorneringAccel) {
                    maxCorneringAccel = aLat
                    maxCorneringSpeed = v
                    maxCorneringYawRate = yawRate
                    // Log.d("TripScorer", "Max cornering updated: aLat=${String.format("%.3f", aLat)} m/s², speed=${String.format("%.2f", v * 3.6)} km/h, yawRate=${String.format("%.4f", yawRate)} rad/s, dBear=${String.format("%.4f", dBear)} rad, dtS=${String.format("%.2f", dtS)} s")
                }
                
                // Cornering thresholds: 200m radius at 45 km/h = 0.781 m/s² (minor)
                // 200m radius at 60 km/h = 1.389 m/s² (mid)
                // Minor: > 0.7 m/s² (catches 200m radius at 45 km/h = 0.781 m/s²)
                // Mid: > 1.3 m/s² (catches 200m radius at 60 km/h = 1.389 m/s²)
                // Major: > 3.0 m/s²
                val turnSeverity = when {
                    aLat > 3.0 -> 3
                    aLat > 1.3 -> 2
                    aLat > 0.7 -> 1
                    else -> 0
                }
                
                if (turnSeverity > 0) {
                    // Log.d("TripScorer", "Cornering detected: aLat=${String.format("%.3f", aLat)} m/s², severity=$turnSeverity (0=none, 1=minor, 2=mid, 3=major), lastTurnTime=${lastTurnTime}, timeSinceLast=${if (lastTurnTime > 0) (now - lastTurnTime) else -1}ms")
                    handleGroupedEvent(
                        now, turnSeverity,
                        ::lastTurnTime, ::lastTurnSeverity,
                        { sev -> when (sev) { 3 -> majorTurns++; 2 -> midTurns++; 1 -> minorTurns++ } },
                        { sev -> when (sev) { 3 -> majorTurns--; 2 -> midTurns--; 1 -> minorTurns-- } }
                    )
                    // Log.d("TripScorer", "After grouping: minor=$minorTurns, mid=$midTurns, major=$majorTurns")
                }
            }
            lastSpeed = v
            lastBearingRad = bearingRad
            lastBearingRadRaw = bearingRadRaw  // Store raw bearing for next cornering calculation
        }

        if (isNight(loc.time) && dtS > 0) nightSeconds += dtS
    }

    fun onPhoneContext(ctx: Context, speedMps: Double) {
        if (!inTrip) return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        val screenOn = pm.isInteractive
        val unlocked = !km.isKeyguardLocked
        val moving = speedMps > 4.0

        // Clean old touch timestamps (older than window)
        val now = System.currentTimeMillis()
        touchTimestamps.removeAll { now - it > TOUCH_WINDOW_MS }
        
        // Only count distraction if: moving, screen on, unlocked, AND has recent touches
        if (moving && screenOn && unlocked) {
            val recentTouches = touchTimestamps.count { now - it <= TOUCH_WINDOW_MS }
            if (recentTouches >= MIN_TOUCHES_FOR_DISTRACTION) {
                handledSeconds += 1.0
            }
        }
        
        // Screen on while moving (for reference, but not penalized heavily)
        if (moving && screenOn) screenOnMovingSeconds += 1.0
    }
    
    fun onTouchEvent() {
        if (!inTrip) return
        touchTimestamps.add(System.currentTimeMillis())
        // Clean old timestamps periodically
        val now = System.currentTimeMillis()
        touchTimestamps.removeAll { now - it > TOUCH_WINDOW_MS }
    }

    fun finishTrip(endEpochMs: Long, routeId: String): Summary {
        inTrip = false

        val durationMin = max(0.0, (endEpochMs - tripStartMs) / 60000.0)
        val distanceKm = distanceM / 1000.0
        val valid = durationMin >= 2.0 && distanceKm >= 0.8
        
        // DIAGNOSTIC: Log scorer state
        android.util.Log.d("TripScorer", "=== FINISHING TRIP ===")
        android.util.Log.d("TripScorer", "Trip start time: $tripStartMs")
        android.util.Log.d("TripScorer", "Trip end time: $endEpochMs")
        android.util.Log.d("TripScorer", "Duration: $durationMin minutes")
        android.util.Log.d("TripScorer", "Distance: $distanceKm km")
        android.util.Log.d("TripScorer", "Valid: $valid (min 2min, 0.8km)")
        android.util.Log.d("TripScorer", "Events - Speeding: minor=$minorSpeeding, mid=$midSpeeding, major=$majorSpeeding")
        android.util.Log.d("TripScorer", "Events - Braking: minor=$minorBrakes, mid=$midBrakes, major=$majorBrakes")
        android.util.Log.d("TripScorer", "Events - Acceleration: minor=$minorAccel, mid=$midAccel, major=$majorAccel")
        android.util.Log.d("TripScorer", "Events - Cornering: minor=$minorTurns, mid=$midTurns, major=$majorTurns")
        android.util.Log.d("TripScorer", "DIAGNOSTIC - Max cornering acceleration: ${String.format("%.3f", maxCorneringAccel)} m/s² (at speed ${String.format("%.2f", maxCorneringSpeed * 3.6)} km/h, yawRate ${String.format("%.4f", maxCorneringYawRate)} rad/s)")

        val score100 = if (!valid) 0.0 else computeScore100(durationMin, distanceKm)
        android.util.Log.d("TripScorer", "Computed score: $score100/100")
        val scoreStars = scoreToStars(score100)

        val trip = TripEntity(
            startEpochMs = tripStartMs,
            endEpochMs = endEpochMs,
            durationMin = durationMin,
            distanceKm = distanceKm,
            routeId = routeId,

            score100 = score100,
            scoreStars = scoreStars,

            minorSpeeding = minorSpeeding,
            midSpeeding = midSpeeding,
            majorSpeeding = majorSpeeding,
            minorAccel = minorAccel,
            midAccel = midAccel,
            majorAccel = majorAccel,
            minorBrakes = minorBrakes,
            midBrakes = midBrakes,
            majorBrakes = majorBrakes,
            minorTurns = minorTurns,
            midTurns = midTurns,
            majorTurns = majorTurns,
            handledSeconds = handledSeconds,
            screenOnMovingSeconds = screenOnMovingSeconds,
            nightMinutes = nightSeconds / 60.0
        )

        val now = System.currentTimeMillis()
        val routeEntity = RouteEntity(
            routeId = routeId,
            firstSeenEpochMs = now,
            lastSeenEpochMs = now,
            tripCount = 1,
            avgScoreStars = scoreStars.toDouble()
        )

        return Summary(trip, routeEntity)
    }

    private fun computeScore100(durationMin: Double, distanceKm: Double): Double {
        // All event penalties: absolute values (not normalized)
        // Minor = 10 points, Mid = 25 points, Major = 45 points
        val spdPenalty = 10.0 * minorSpeeding + 25.0 * midSpeeding + 45.0 * majorSpeeding
        val accPenalty = 10.0 * minorAccel + 25.0 * midAccel + 45.0 * majorAccel
        val brkPenalty = 10.0 * minorBrakes + 25.0 * midBrakes + 45.0 * majorBrakes
        val corPenalty = 10.0 * minorTurns + 25.0 * midTurns + 45.0 * majorTurns

        val durPenalty = max(0.0, (durationMin - 90.0) / 30.0) * 1.0

        // Distraction only counts if there are actual touches (handledSeconds already filtered)
        // Screen on alone is not penalized
        val disPenalty = 5.0 * (handledSeconds / 60.0)

        val raw = spdPenalty + accPenalty + brkPenalty + corPenalty + durPenalty + disPenalty
        val nightFactor = if (nightSeconds > 0.0) 1.2 else 1.0

        return clamp(100.0 - raw * nightFactor, 0.0, 100.0)
    }

    private fun scoreToStars(score100: Double): Int =
        kotlin.math.round(score100 / 20.0).toInt().coerceIn(0, 5)

    private fun clamp(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))

    private fun wrapAngleRad(a: Double): Double {
        var x = a
        while (x > Math.PI) x -= 2.0 * Math.PI
        while (x < -Math.PI) x += 2.0 * Math.PI
        return x
    }

    private fun isNight(epochMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return (h >= 23) || (h < 5)
    }
    
    /**
     * Handles event grouping: successive events of the same type within 5 seconds count as 1 event.
     * The event is categorized based on the worst severity in the group.
     * 
     * @param now Current timestamp in milliseconds
     * @param newSeverity Severity of the new event (1=minor, 2=mid, 3=major)
     * @param lastTimeRef Reference to the last event timestamp for this event type
     * @param lastSeverityRef Reference to the current severity level for the active group
     * @param incrementCounter Callback to increment the appropriate counter
     * @param decrementCounter Callback to decrement the appropriate counter
     */
    private fun handleGroupedEvent(
        now: Long,
        newSeverity: Int,
        lastTimeRef: () -> Long,
        lastSeverityRef: () -> Int,
        incrementCounter: (Int) -> Unit,
        decrementCounter: (Int) -> Unit
    ) {
        val lastTime = lastTimeRef()
        val lastSeverity = lastSeverityRef()
        
        if (lastTime == 0L || (now - lastTime) > EVENT_GROUPING_WINDOW_MS) {
            // New event group: increment counter and start tracking
            incrementCounter(newSeverity)
            updateEventState(lastTimeRef, lastSeverityRef, now, newSeverity)
        } else {
            // Within grouping window
            if (newSeverity > lastSeverity) {
                // Upgrade severity if worse: decrement old severity, increment new severity
                decrementCounter(lastSeverity)
                incrementCounter(newSeverity)
                updateEventState(lastTimeRef, lastSeverityRef, now, newSeverity)
            } else if (newSeverity < lastSeverity && (now - lastTime) > EVENT_GROUPING_SEVERITY_RESET_MS) {
                // Lower severity event after higher severity: if enough time has passed (3s), start new group
                // This allows minor events after major events to be counted separately
                incrementCounter(newSeverity)
                updateEventState(lastTimeRef, lastSeverityRef, now, newSeverity)
            } else {
                // Same severity, or lower severity but too soon: just update timestamp to extend the window
                updateEventState(lastTimeRef, lastSeverityRef, now, lastSeverity)
            }
        }
    }
    
    private fun updateEventState(
        lastTimeRef: () -> Long,
        lastSeverityRef: () -> Int,
        newTime: Long,
        newSeverity: Int
    ) {
        // Since we can't directly modify the references, we need to use a different approach
        // We'll use a helper function that works with the actual properties
        when (lastTimeRef) {
            ::lastSpeedingTime -> {
                lastSpeedingTime = newTime
                lastSpeedingSeverity = newSeverity
            }
            ::lastAccelTime -> {
                lastAccelTime = newTime
                lastAccelSeverity = newSeverity
            }
            ::lastBrakeTime -> {
                lastBrakeTime = newTime
                lastBrakeSeverity = newSeverity
            }
            ::lastTurnTime -> {
                lastTurnTime = newTime
                lastTurnSeverity = newSeverity
            }
        }
    }
    
    // Expose current event counts for real-time tracking
    fun getCurrentEvents(): EventCounts {
        return EventCounts(
            minorSpeeding = minorSpeeding,
            midSpeeding = midSpeeding,
            majorSpeeding = majorSpeeding,
            minorAccel = minorAccel,
            midAccel = midAccel,
            majorAccel = majorAccel,
            minorBrakes = minorBrakes,
            midBrakes = midBrakes,
            majorBrakes = majorBrakes,
            minorTurns = minorTurns,
            midTurns = midTurns,
            majorTurns = majorTurns,
            handledSeconds = handledSeconds
        )
    }
    
    fun getCurrentDistance(): Double {
        return distanceM
    }
    
    data class EventCounts(
        val minorSpeeding: Int,
        val midSpeeding: Int,
        val majorSpeeding: Int,
        val minorAccel: Int,
        val midAccel: Int,
        val majorAccel: Int,
        val minorBrakes: Int,
        val midBrakes: Int,
        val majorBrakes: Int,
        val minorTurns: Int,
        val midTurns: Int,
        val majorTurns: Int,
        val handledSeconds: Double
    )
}
