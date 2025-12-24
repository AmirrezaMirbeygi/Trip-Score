package com.tripscore.app.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object TripServiceController {
    fun start(ctx: Context) {
        val i = Intent(ctx, TripRecorderService::class.java).apply {
            action = TripRecorderService.ACTION_START
        }
        ContextCompat.startForegroundService(ctx, i)
    }

    fun stop(ctx: Context) {
        val i = Intent(ctx, TripRecorderService::class.java).apply {
            action = TripRecorderService.ACTION_STOP
        }
        ctx.startService(i)
    }

    fun isRunning(ctx: Context): Boolean {
        val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == TripRecorderService::class.java.name }
    }
}
