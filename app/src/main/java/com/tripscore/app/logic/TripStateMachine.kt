package com.tripscore.app.logic

import android.location.Location
import kotlin.math.max

class TripStateMachine {

    data class Update(
        val tripStarted: Boolean = false,
        val tripOngoing: Boolean = false,
        val tripEnded: Boolean = false,
        val tripStartEpochMs: Long = 0L,
        val tripEndEpochMs: Long = 0L
    )

    private var inTrip = false
    private var tripStartMs: Long = 0L

    private var highSpeedAccumMs: Long = 0L
    private var startCandidateMs: Long = 0L
    private var startCandidateDistM: Double = 0.0
    private var lastLocForStart: Location? = null

    private var lowSpeedAccumMs: Long = 0L
    private var lastTsMs: Long = 0L

    fun onLocation(loc: Location): Update {
        val now = loc.time
        val dt = if (lastTsMs == 0L) 0L else max(0L, now - lastTsMs)
        lastTsMs = now

        val speed = loc.speed.toDouble()
        val isHigh = speed > 2.22      // > 8 km/h
        val isLow = speed < 0.83       // < 3 km/h

        if (!inTrip) {
            if (isHigh) {
                if (startCandidateMs == 0L) {
                    startCandidateMs = now
                    startCandidateDistM = 0.0
                    lastLocForStart = loc
                    highSpeedAccumMs = 0L
                }
                highSpeedAccumMs += dt
                lastLocForStart?.let { prev ->
                    startCandidateDistM += prev.distanceTo(loc).toDouble()
                }
                lastLocForStart = loc

                val okTime = highSpeedAccumMs >= 20_000L
                val okDist = startCandidateDistM >= 150.0
                if (okTime && okDist) {
                    inTrip = true
                    tripStartMs = startCandidateMs

                    lowSpeedAccumMs = 0L
                    startCandidateMs = 0L
                    startCandidateDistM = 0.0
                    lastLocForStart = null
                    highSpeedAccumMs = 0L

                    return Update(tripStarted = true, tripOngoing = true, tripStartEpochMs = tripStartMs)
                }
            } else {
                startCandidateMs = 0L
                startCandidateDistM = 0.0
                lastLocForStart = null
                highSpeedAccumMs = 0L
            }
            return Update()
        } else {
            if (isLow) lowSpeedAccumMs += dt else lowSpeedAccumMs = 0L
            val ended = lowSpeedAccumMs >= 5 * 60_000L
            return if (ended) {
                inTrip = false
                val endMs = now
                lowSpeedAccumMs = 0L
                Update(tripEnded = true, tripEndEpochMs = endMs)
            } else {
                Update(tripOngoing = true)
            }
        }
    }
}
