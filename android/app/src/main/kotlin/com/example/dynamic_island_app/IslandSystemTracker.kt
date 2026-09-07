package com.example.dynamic_island_app

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Monitors system hardware events and forwards state to [DynamicIsland].
 *
 * SECURITY: all data is RAM-only and transient; no content is written
 * to disk or transmitted. Privacy-dot monitoring (camera/mic) is opt-in
 * and requires the user to grant PACKAGE_USAGE_STATS in Settings.
 */
object IslandSystemTracker {

    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var opsWatcher: AppOpsManager.OnOpActiveChangedListener? = null

    // Battery: track last reported level to avoid repeat flashes
    private var lastBatteryPct = -1
    private var lastCharging = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        registerReceivers(app)
        startOpsWatcher(app)
    }

    fun stop(context: Context) {
        if (!started) return
        started = false
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        stopOpsWatcher(app)
        lastBatteryPct = -1
        lastCharging = false
        DynamicIsland.currentSystemState = null
        DynamicIsland.cameraActive = false
        DynamicIsland.micActive = false
    }

    // ------------------------------------------------------------------
    // Broadcast receivers
    // ------------------------------------------------------------------

    private fun registerReceivers(app: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                main.post { handleIntent(app, intent) }
            }
        }
        app.registerReceiver(r, filter)
        receiver = r
    }

    private fun handleIntent(app: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> onChargingChanged(app, true)
            Intent.ACTION_POWER_DISCONNECTED -> onChargingChanged(app, false)
            Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
            AudioManager.RINGER_MODE_CHANGED_ACTION -> onRingerChanged(app)
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> onDndChanged(app)
        }
    }

    private fun onChargingChanged(app: Context, charging: Boolean) {
        if (charging == lastCharging) return
        lastCharging = charging
        val pct = currentBatteryPct(app)
        if (charging) {
            flash(SystemStateEvent(
                iconRes   = R.drawable.ic_charging,
                label     = "Charging",
                bodyText  = "$pct%",
                accentColor = 0xFF30D158.toInt(),
                holdMs    = 3000L
            ))
        } else {
            flash(SystemStateEvent(
                iconRes   = R.drawable.ic_battery,
                label     = "Unplugged",
                bodyText  = "$pct%",
                accentColor = 0xFFFFFFFF.toInt(),
                holdMs    = 2000L
            ))
        }
    }

    private fun onBatteryChanged(intent: Intent) {
        val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        if (charging || pct < 0) return          // charging alerts handled separately
        if (pct == lastBatteryPct) return
        lastBatteryPct = pct
        when {
            pct <= 10 -> flash(SystemStateEvent(
                iconRes   = R.drawable.ic_battery_low,
                label     = "Charge Now",
                bodyText  = "$pct%",
                accentColor = 0xFFFF453A.toInt(),
                holdMs    = 5000L
            ))
            pct <= 20 -> flash(SystemStateEvent(
                iconRes   = R.drawable.ic_battery_low,
                label     = "Low Battery",
                bodyText  = "$pct%",
                accentColor = 0xFFFFD60A.toInt(),
                holdMs    = 4000L
            ))
        }
    }

    private fun onRingerChanged(app: Context) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val event = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> SystemStateEvent(
                R.drawable.ic_silent,  "Silent",  "", 0xFFFFFFFF.toInt(), 2000L)
            AudioManager.RINGER_MODE_VIBRATE -> SystemStateEvent(
                R.drawable.ic_vibrate, "Vibrate", "", 0xFFFFFFFF.toInt(), 2000L)
            else -> SystemStateEvent(
                R.drawable.ic_ring,    "Ring",    "", 0xFFFFFFFF.toInt(), 2000L)
        }
        flash(event)
    }

    private fun onDndChanged(app: Context) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val on = nm.currentInterruptionFilter !=
                 NotificationManager.INTERRUPTION_FILTER_ALL
        flash(SystemStateEvent(
            iconRes   = R.drawable.ic_focus,
            label     = if (on) "Focus On" else "Focus Off",
            bodyText  = "",
            accentColor = if (on) 0xFF5E5CE6.toInt() else 0xFFFFFFFF.toInt(),
            holdMs    = 2000L
        ))
    }

    private fun flash(event: SystemStateEvent) {
        DynamicIsland.onSystemStateChanged(event)
        main.postDelayed({ DynamicIsland.onSystemStateChanged(null) }, event.holdMs)
    }

    private fun currentBatteryPct(app: Context): Int {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (scale > 0) level * 100 / scale else -1
    }

    // ------------------------------------------------------------------
    // AppOps: camera + microphone privacy monitoring
    // ------------------------------------------------------------------

    private fun startOpsWatcher(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val aom = app.getSystemService(AppOpsManager::class.java) ?: return
        // GET_USAGE_STATS check: if not granted the call simply does nothing or throws
        val ops = arrayOf(AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO)
        val listener = AppOpsManager.OnOpActiveChangedListener { _, _, op, active ->
            main.post {
                when (op) {
                    AppOpsManager.OPSTR_CAMERA -> DynamicIsland.cameraActive = active
                    AppOpsManager.OPSTR_RECORD_AUDIO -> DynamicIsland.micActive = active
                }
                DynamicIsland.onPrivacyChanged(DynamicIsland.cameraActive, DynamicIsland.micActive)
            }
        }
        runCatching {
            aom.startWatchingActive(ops, app.mainExecutor, listener)
            opsWatcher = listener
        }
        // If PACKAGE_USAGE_STATS is not granted, startWatchingActive throws SecurityException
        // and opsWatcher stays null — privacy dots simply stay hidden.
    }

    private fun stopOpsWatcher(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val watcher = opsWatcher ?: return
        val aom = app.getSystemService(AppOpsManager::class.java) ?: return
        runCatching { aom.stopWatchingActive(watcher) }
        opsWatcher = null
    }
}
