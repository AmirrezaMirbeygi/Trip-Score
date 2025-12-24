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
    private var majorSpeeding = 0
    private var moderateAccel = 0
    private var aggressiveAccel = 0
    private var hardBrakes = 0
    private var panicBrakes = 0
    private var sharpTurns = 0
    private var aggressiveTurns = 0

    private var handledSeconds = 0.0
    private var screenOnMovingSeconds = 0.0
    private var nightSeconds = 0.0

    // v1: no posted speed limits, so use a conservative baseline.
    private val DEFAULT_LIMIT_MPS = 27.78 // 100 km/h
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
        majorSpeeding = 0
        moderateAccel = 0
        aggressiveAccel = 0
        hardBrakes = 0
        panicBrakes = 0
        sharpTurns = 0
        aggressiveTurns = 0

        handledSeconds = 0.0
        screenOnMovingSeconds = 0.0
        nightSeconds = 0.0
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

        // Speeding (placeholder): compare to DEFAULT_LIMIT_MPS
        if (v > DEFAULT_LIMIT_MPS * 1.10) minorSpeeding += 1
        if (v > DEFAULT_LIMIT_MPS * 1.20) majorSpeeding += 1

        if (dtS >= MIN_DT_S) {
            val aLong = (v - lastSpeed) / dtS
            if (v > MIN_SPEED_FOR_EVENTS) {
                if (aLong > 2.5) moderateAccel += 1
                if (aLong > 3.5) aggressiveAccel += 1
                if (aLong < -3.0) hardBrakes += 1
                if (aLong < -4.5) panicBrakes += 1

                val dBear = wrapAngleRad(bearingRad - lastBearingRad)
                val yawRate = dBear / dtS
                val aLat = abs(v * yawRate)
                if (aLat > 2.8) sharpTurns += 1
                if (aLat > 3.8) aggressiveTurns += 1
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

        if (moving && screenOn) screenOnMovingSeconds += 1.0
        if (moving && screenOn && unlocked) handledSeconds += 1.0
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
            majorSpeeding = majorSpeeding,
            moderateAccel = moderateAccel,
            aggressiveAccel = aggressiveAccel,
            hardBrakes = hardBrakes,
            panicBrakes = panicBrakes,
            sharpTurns = sharpTurns,
            aggressiveTurns = aggressiveTurns,
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

        val spdPenalty = 1.0 * (minorSpeeding / norm) + 3.0 * (majorSpeeding / norm)
        val accPenalty = 1.0 * (moderateAccel / norm) + 2.5 * (aggressiveAccel / norm)
        val brkPenalty = 1.5 * (hardBrakes / norm) + 3.0 * (panicBrakes / norm)
        val corPenalty = 1.0 * (sharpTurns / norm) + 2.5 * (aggressiveTurns / norm)

        val durPenalty = max(0.0, (durationMin - 90.0) / 30.0) * 1.0

        val disPenalty =
            5.0 * (handledSeconds / 60.0) +
            0.2 * (screenOnMovingSeconds / 60.0)

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
}
