package com.tripscore.app.logic

import android.app.KeyguardManager
import android.content.Context
import android.location.Location
import android.os.PowerManager
import com.tripscore.app.data.RouteEntity
import com.tripscore.app.data.TripEntity
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
    private var lastTs = 0L

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

    // v1: no posted speed limits, so use a baseline.
    private val DEFAULT_LIMIT_MPS = 25.0 // ~90 km/h (less conservative)
    private val MIN_SPEED_FOR_EVENTS = 4.0 // ~14.4 km/h
    private val MIN_DT_S = 0.4

    fun startTrip(startEpochMs: Long) {
        inTrip = true
        tripStartMs = startEpochMs

        distanceM = 0.0
        lastLoc = null
        lastSpeed = 0.0
        lastBearingRad = 0.0
        lastTs = 0L

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
    }

    fun onLocation(loc: Location) {
        if (!inTrip) return
        val prev = lastLoc
        val now = loc.time
        if (prev != null) distanceM += prev.distanceTo(loc).toDouble()

        val dtS = if (lastTs == 0L) 0.0 else max(0.0, (now - lastTs) / 1000.0)
        lastTs = now
        lastLoc = loc

        val v = loc.speed.toDouble().coerceAtLeast(0.0)
        val bearingRad = Math.toRadians(loc.bearing.toDouble())

        // Speeding: minor = 10 km/h over, mid = 20 km/h over, major = 30 km/h over
        // 10 km/h = 2.78 m/s, 20 km/h = 5.56 m/s, 30 km/h = 8.33 m/s
        val minorThreshold = DEFAULT_LIMIT_MPS + 2.78  // ~100 km/h (10 km/h over)
        val midThreshold = DEFAULT_LIMIT_MPS + 5.56    // ~110 km/h (20 km/h over)
        val majorThreshold = DEFAULT_LIMIT_MPS + 8.33   // ~120 km/h (30 km/h over)
        if (v > majorThreshold) majorSpeeding += 1
        else if (v > midThreshold) midSpeeding += 1
        else if (v > minorThreshold) minorSpeeding += 1

        if (dtS >= MIN_DT_S) {
            val aLong = (v - lastSpeed) / dtS
            if (v > MIN_SPEED_FOR_EVENTS) {
                // Acceleration: current thresholds become minor, add mid and major
                // Minor: > 3.5 m/s² (current moderate)
                // Mid: > 4.5 m/s² (current aggressive)
                // Major: > 6.0 m/s² (new, more aggressive)
                if (aLong > 6.0) majorAccel += 1
                else if (aLong > 4.5) midAccel += 1
                else if (aLong > 3.5) minorAccel += 1
                
                // Braking: rename to minor/mid/major
                // Minor: -2.0 to -3.0 (current mild)
                // Mid: -3.0 to -4.5 (current hard)
                // Major: < -4.5 (current panic)
                if (aLong < -4.5) {
                    majorBrakes += 1
                } else if (aLong < -3.0) {
                    midBrakes += 1
                } else if (aLong < -2.0) {
                    minorBrakes += 1
                }

                val dBear = wrapAngleRad(bearingRad - lastBearingRad)
                val yawRate = dBear / dtS
                val aLat = abs(v * yawRate)
                // Cornering: current thresholds become minor, add mid and major
                // Minor: > 3.5 m/s² (current sharp)
                // Mid: > 4.5 m/s² (current aggressive)
                // Major: > 6.0 m/s² (new, more aggressive)
                if (aLat > 6.0) majorTurns += 1
                else if (aLat > 4.5) midTurns += 1
                else if (aLat > 3.5) minorTurns += 1
            }
            lastSpeed = v
            lastBearingRad = bearingRad
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

        val score100 = if (!valid) 0.0 else computeScore100(durationMin, distanceKm)
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
        val norm = (distanceKm / 10.0).coerceAtLeast(0.3)

        // Speed: minor = 1.0x, mid = 2.0x, major = 3.0x
        val spdPenalty = 1.0 * (minorSpeeding / norm) + 2.0 * (midSpeeding / norm) + 3.0 * (majorSpeeding / norm)
        
        // Acceleration: minor = 1.0x, mid = 2.0x, major = 2.5x
        val accPenalty = 1.0 * (minorAccel / norm) + 2.0 * (midAccel / norm) + 2.5 * (majorAccel / norm)
        
        // Braking penalties: absolute values (not normalized)
        // Minor = 25 points, Mid = 50 points, Major = 75 points
        val brkPenalty = 25.0 * minorBrakes + 50.0 * midBrakes + 75.0 * majorBrakes
        
        // Cornering: minor = 1.0x, mid = 2.0x, major = 2.5x
        val corPenalty = 1.0 * (minorTurns / norm) + 2.0 * (midTurns / norm) + 2.5 * (majorTurns / norm)

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
