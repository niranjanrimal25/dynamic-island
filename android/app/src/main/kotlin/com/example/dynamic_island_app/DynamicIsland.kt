package com.example.dynamic_island_app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.flutter.plugin.common.EventChannel

/**
 * One transient alert captured from [NotificationListener].
 *
 * SECURITY: instances exist ONLY in memory (in a Kotlin object / local
 * variable / the views that render them) for the seconds the island shows
 * them. They are never written to disk and never leave the process.
 */
class NotificationAlert(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val icon: Drawable?
)

/**
 * Live media-session snapshot (in-memory only, like [NotificationAlert]).
 * The [controller] is used solely to drive playback from the island's
 * transport buttons; nothing here is persisted or transmitted.
 */
class MediaState(
    val appLabel: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val controller: MediaController
)

/** Live countdown / stopwatch state. RAM only — a restart drops it by design. */
enum class TimerKind { COUNTDOWN, STOPWATCH }

class TimerState(
    val kind: TimerKind,
    val startedAtElapsedMs: Long,
    val durationMs: Long = 0L
)

/**
 * App-wide wiring for the Isle dynamic-island feature.
 *
 * SECURITY MODEL (enforced everywhere in this app):
 *  - [NotificationAlert] objects live only in memory.
 *  - The ONLY thing persisted on disk is the user's ON/OFF switch
 *    ([KEY_OVERLAY_ENABLED]). Notification *content* never touches
 *    SharedPreferences, files, or the network.
 *  - No analytics / crash-reporting / telemetry SDKs exist in this app.
 *  - No notification content is ever logged.
 */
object DynamicIsland {
    const val TAG = "DynamicIsland"

    private const val PREFS_NAME = "dynamic_island_prefs"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

    const val SERVICE_CHANNEL_ID = "dynamic_island_service"
    const val SERVICE_NOTIFICATION_ID = 9001

    // If an alert arrives while the overlay service is not alive yet, it is
    // held here (in memory only) until the service starts and consumes it.
    @Volatile
    var pendingAlert: NotificationAlert? = null

    // ------------------------------------------------------------------
    // Live "persistent mode" state — all in memory, never persisted.
    // Priority when several are active at once: CALL > TIMER > MEDIA >
    // notification flash (see hasPersistentMode / dispatchAlert).
    // ------------------------------------------------------------------

    /** Active media session (any app) tracked by [IslandMediaTracker]. */
    @Volatile
    var mediaState: MediaState? = null

    /** elapsedRealtime() at which the current call went off-hook. */
    @Volatile
    var callStartedAtElapsedMs: Long? = null

    /** Running countdown/stopwatch started from the app UI. */
    @Volatile
    var timerState: TimerState? = null

    fun hasPersistentMode(): Boolean =
        callStartedAtElapsedMs != null || timerState != null || mediaState != null

    /** Called by the trackers whenever live state changes (any thread). */
    fun onLiveStateChanged() {
        OverlayForegroundService.instance?.refresh()
        notifyStatusChanged()
    }

    fun startCountdown(seconds: Int) {
        timerState = TimerState(
            TimerKind.COUNTDOWN,
            SystemClock.elapsedRealtime(),
            seconds * 1000L
        )
        onLiveStateChanged()
    }

    fun startStopwatch() {
        timerState = TimerState(TimerKind.STOPWATCH, SystemClock.elapsedRealtime())
        onLiveStateChanged()
    }

    fun stopTimer() {
        if (timerState == null) return
        timerState = null
        onLiveStateChanged()
    }

    // Live status-event sink. Non-null only while the Flutter UI is attached.
    @Volatile
    private var statusSink: EventChannel.EventSink? = null

    private const val STATUS_EVENT = "status"

    // Handles are only used to hop onto the main thread for EventChannel calls.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun setStatusSink(sink: EventChannel.EventSink?) {
        statusSink = sink
    }

    /** Ask the Flutter UI (if open) to re-read the status flags. */
    fun notifyStatusChanged() {
        statusSink?.success(STATUS_EVENT)
    }

    // Alert *previews* forwarded to the Flutter UI while it is open (the app's
    // in-app island preview mirrors what the native overlay is showing).
    // These travel over an in-process channel only — never persisted/sent out.
    @Volatile
    private var alertSink: EventChannel.EventSink? = null

    fun setAlertSink(sink: EventChannel.EventSink?) {
        alertSink = sink
    }

    private fun forwardAlertPreview(alert: NotificationAlert) {
        val sink = alertSink ?: return
        val data: Map<String, String> = mapOf(
            "packageName" to alert.packageName,
            "appLabel" to alert.appLabel,
            "title" to alert.title,
            "text" to alert.text
        )
        mainHandler.post { sink.success(data) }
    }

    // ------------------------------------------------------------------
    // Persisted configuration — user preference ONLY, never content.
    // ------------------------------------------------------------------

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_ENABLED, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    // ------------------------------------------------------------------
    // Permission / capability checks (queried by the settings UI)
    // ------------------------------------------------------------------

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)

    fun isNotificationAccessGranted(context: Context): Boolean {
        val target = ComponentName(context, NotificationListener::class.java).flattenToString()
        return try {
            val raw = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            raw.split(":").contains(target)
        } catch (e: SecurityException) {
            false
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        // Battery-optimization APIs only exist from API 23; older versions
        // have no doze restrictions to worry about, so treat them as exempt.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts the foreground overlay service. No-op unless the overlay
     * permission is currently granted (otherwise Android would reject the
     * window anyway and kill the service).
     */
    fun startOverlayService(context: Context) {
        val app = context.applicationContext
        if (!canDrawOverlays(app)) {
            Log.w(TAG, "Overlay permission not granted; not starting service")
            return
        }
        val intent = Intent(app, OverlayForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException when Android
            // refuses a background start. Nothing to log beyond the class.
            Log.w(TAG, "Could not start overlay service: ${t.javaClass.simpleName}")
        }
    }

    fun stopOverlayService(context: Context) {
        // Drop anything held for a not-yet-running service (in-memory only).
        pendingAlert = null
        context.applicationContext.stopService(
            Intent(context.applicationContext, OverlayForegroundService::class.java)
        )
    }

    fun isOverlayServiceRunning(): Boolean =
        OverlayForegroundService.instance != null

    // ------------------------------------------------------------------
    // Alert flow (NotificationListener -> overlay service)
    // ------------------------------------------------------------------

    /** Single entry point for an incoming alert (called on the listener thread). */
    fun dispatchAlert(context: Context, alert: NotificationAlert) {
        if (!isOverlayEnabled(context)) return
        forwardAlertPreview(alert)
        // Priority order: call > timer > media > notification flash. A flash
        // never interrupts a live persistent mode.
        if (hasPersistentMode()) return
        OverlayForegroundService.instance?.let {
            it.showAlert(alert)
            return
        }
        if (!canDrawOverlays(context)) return
        pendingAlert = alert
        startOverlayService(context)
    }

    /** Notification dismissed by the user or the app in the shade. */
    fun onNotificationRemoved(context: Context, key: String) {
        if (!isOverlayEnabled(context)) return
        OverlayForegroundService.instance?.onAlertRemoved(key)
    }
}
