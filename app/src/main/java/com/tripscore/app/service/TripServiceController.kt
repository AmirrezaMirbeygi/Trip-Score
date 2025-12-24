package com.tripscore.app.service

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
}
