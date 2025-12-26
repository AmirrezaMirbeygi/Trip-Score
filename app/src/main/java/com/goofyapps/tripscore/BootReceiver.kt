package com.goofyapps.tripscore

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.goofyapps.tripscore.service.TripServiceController
import com.goofyapps.tripscore.service.TripRecorderService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED) {
            // Check permissions before starting service
            val hasFineLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PermissionChecker.PERMISSION_GRANTED
            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PermissionChecker.PERMISSION_GRANTED
            
            // Only start if permissions granted and not explicitly stopped
            if ((hasFineLocation || hasCoarseLocation) && 
                !TripRecorderService.isExplicitlyStopped(context)) {
                TripServiceController.start(context)
            }
        }
    }
}
