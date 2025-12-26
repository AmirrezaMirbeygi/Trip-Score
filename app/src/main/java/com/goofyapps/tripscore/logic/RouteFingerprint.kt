package com.goofyapps.tripscore.logic

import android.location.Location
import java.security.MessageDigest
import kotlin.math.roundToInt

class RouteFingerprint {
    private val tiles = mutableListOf<String>()
    private var last: Location? = null

    fun start() {
        tiles.clear()
        last = null
    }

    fun onLocation(loc: Location) {
        val prev = last
        if (prev != null && prev.distanceTo(loc) < 100f) return
        last = loc
        tiles.add(tile(loc.latitude, loc.longitude))
    }

    fun finish(): String {
        val joined = tiles.joinToString("|")
        return sha256(joined)
    }

    private fun tile(lat: Double, lon: Double): String {
        val qLat = (lat / 0.001).roundToInt()
        val qLon = (lon / 0.001).roundToInt()
        return "${qLat}_${qLon}"
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }
}
