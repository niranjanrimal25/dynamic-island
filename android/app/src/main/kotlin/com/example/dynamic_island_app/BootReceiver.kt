package com.example.dynamic_island_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the overlay service automatically after the phone reboots
 * (BOOT_COMPLETED) or after the app is updated (MY_PACKAGE_REPLACED).
 *
 * SECURITY: no notification data flows through this receiver. It only
 * checks the user's persisted ON/OFF preference (a boolean, never content)
 * and, if the overlay permission is still granted, restarts the service.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // Only auto-start when the user had the feature ON and the OS still
        // allows overlays for this app.
        if (!DynamicIsland.isOverlayEnabled(context)) return
        if (!DynamicIsland.canDrawOverlays(context)) return
        DynamicIsland.startOverlayService(context)
    }
}
