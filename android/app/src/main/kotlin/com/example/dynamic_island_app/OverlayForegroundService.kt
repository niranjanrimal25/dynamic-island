package com.example.dynamic_island_app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Foreground service that owns the floating "island" window.
 *
 * The pill is a single unified component with several modes. Persistent
 * modes resolve by priority (call > timer > media > idle); a notification
 * FLASH is a TEMPORARY interrupt that shows on top of any persistent mode
 * and reverts to it when the flash collapses (touches pass through while
 * flashing):
 *  - IDLE:  pure-black compact capsule blended into the punch-hole camera,
 *           touches pass straight through (FLAG_NOT_TOUCHABLE).
 *  - FLASH: a captured notification expands the pill briefly, then it
 *           collapses back to whatever persistent mode was underneath.
 *  - MEDIA: persistent compact view (album art + animated waveform) while a
 *           media session of any app is playing; tap expands to title/artist
 *           + real play/pause/skip transport controls (MediaController).
 *  - CALL:  persistent live call-duration timer while a call is off-hook.
 *  - TIMER: persistent live countdown/stopwatch fed by the app's timer UI.
 *
 * SECURITY:
 *  - Renders the *current* in-memory alert / media / call / timer state only
 *    while visible, then clears the views (see [clearContent]).
 *  - NEVER writes notification/media/call content anywhere; the only disk
 *    state in the app is the user's ON/OFF preference (see [DynamicIsland]).
 *  - The persistent service notification shows a generic label only.
 *  - Nothing here touches the network; no manifest variant has INTERNET.
 *  - The overlay never draws over the secure lock screen: it is a plain
 *    TYPE_APPLICATION_OVERLAY without FLAG_SHOW_WHEN_LOCKED, and Android
 *    hides such windows above a PIN/pattern/biometric keyguard by design.
 */
class OverlayForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "dynamic_island_service"
        private const val NOTIFICATION_ID = 9001

        // Time budget: expand 220 ms, hold ~4.2 s (longer for longer text),
        // collapse 260 ms.
        private const val EXPAND_MS = 220L
        private const val HOLD_MS = 4200L
        private const val COLLAPSE_MS = 260L

        /** Extra hold time per character of body text, capped. */
        private const val HOLD_PER_CHAR_MS = 8L
        private const val MAX_EXTRA_HOLD_MS = 4000L

        /** Body wraps over at most this many lines, then ellipsizes. */
        private const val MAX_BODY_LINES = 4

        /** Upper bound for the flash-expanded pill height. */
        private const val MAX_EXPANDED_HEIGHT_DP = 160

        /** Live tick for the call / timer readouts. */
        private const val TICK_MS = 250L

        /** How long the unlock flourish stays on the compact pill. */
        private const val UNLOCK_MS = 1400L

        private const val FLASH_COMPACT_HOLD_MS = 500L
        private const val FLASH_COMPACT_W_DP = 80
        private const val FLASH_COMPACT_H_DP = 44

        /** Non-null while this service is alive (guarded by @Volatile). */
        @Volatile
        var instance: OverlayForegroundService? = null
            private set
    }

    private enum class Mode { IDLE, FLASH, UNLOCK, MEDIA, CALL, TIMER }

    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private lateinit var iconView: ImageView
    private lateinit var unlockView: ImageView
    private lateinit var eqContainer: LinearLayout
    private lateinit var eqBars: List<View>
    private lateinit var appLabelView: TextView
    private lateinit var bodyView: TextView
    private lateinit var textColumn: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton

    private var expandedWidthPx = 0
    private var compactWidthPx = 0
    private var compactHeightPx = 0

    private var mode = Mode.IDLE
    private var lastPersistent: Mode? = null
    private var userExpanded = false
    private var currentAlert: NotificationAlert? = null

    /**
     * Tap-to-open arming: a flash only becomes tappable once its expand
     * animation finished, so an accidental tap while it grows can't hit it.
     * Tap toggles the flash expanded/compact; long-press opens the source
     * app (tap-to-open).
     */
    private var flashArmed = false
    private lateinit var labelRow: LinearLayout
    private lateinit var timestampView: TextView

    /** Flash detail state: true = full body, false = compact icon pill. */
    private var flashExpanded = false
    private var flashStartedAtMs = 0L

    private val flashArmedRunnable = Runnable {
        if (mode == Mode.FLASH) {
            flashArmed = true
            applyTouchability(true)
        }
    }

    private val flashExpandRunnable = Runnable {
        if (mode == Mode.FLASH) {
            flashExpanded = true
            render()
        }
    }

    private var shapeAnimator: ValueAnimator? = null
    private var fadeAnimator: AnimatorSet? = null
    private var eqAnimator: ValueAnimator? = null
    private var unlockPulse: ValueAnimator? = null
    private var tickerOn = false

    /** True between ACTION_USER_PRESENT and the end of the flourish. */
    private var unlockActive = false

    private val unlockEndRunnable = Runnable {
        unlockActive = false
        render()
    }

    /**
     * Unlock flourish trigger. ACTION_USER_PRESENT fires the moment the
     * device becomes unlocked & interactive, regardless of the unlock method
     * (Android deliberately does not let apps observe WHICH method was
     * used). The overlay window is hidden by the OS on a secure lock screen,
     * so this can only ever play after unlock — no lock-screen bypass, no
     * biometric APIs.
     */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT &&
                context != null &&
                DynamicIsland.isUnlockFlourishEnabled(context)
            ) {
                showUnlock()
            }
        }
    }

    private fun showUnlock() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { showUnlock() }
            return
        }
        unlockActive = true
        main.removeCallbacks(unlockEndRunnable)
        main.postDelayed(unlockEndRunnable, UNLOCK_MS)
        render()
    }

    /** Spring-like expand (slight overshoot, iOS-island feel). */
    private val springExpand: TimeInterpolator =
        PathInterpolator(0.34f, 1.56f, 0.64f, 1f)
    private val easeCollapse: TimeInterpolator = AccelerateInterpolator()

    private val hideRunnable = Runnable {
        main.removeCallbacks(flashExpandRunnable)
        currentAlert?.let { DynamicIsland.dropContentIntent(it.key) }
        currentAlert = null
        flashArmed = false
        render()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            tickOnce()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        createWindow()
        // Standard, public unlock broadcast — fires after ANY unlock method.
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        IslandMediaTracker.start(this)
        IslandCallTracker.start(this)
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground quickly after startForegroundService().
        startForegroundInternal()

        // Consume an alert that arrived while the process/service was down
        // (this runs on the main thread; the listener wrote it before starting us).
        DynamicIsland.pendingAlert?.let { pending ->
            DynamicIsland.pendingAlert = null
            showAlert(pending)
        }
        // An explicit empty start (e.g. after a settings-screen restart request)
        // simply keeps the service alive and shows the idle compact pill.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away from Recents: keep running (stopWithTask=false also).
        // No-op on purpose.
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        cancelAnimations()
        stopEq()
        stopUnlockPulse()
        runCatching { unregisterReceiver(unlockReceiver) }
        // SECURITY: never outlive the window with stored tap targets.
        DynamicIsland.dropAllContentIntents()
        IslandMediaTracker.stop(this)
        IslandCallTracker.stop(this)
        removeWindow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground notification (generic only — privacy by design)
    // ------------------------------------------------------------------

    private fun startForegroundInternal() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_MIN)
        }
        builder
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle("Notification overlay running")
            .setContentText("Isle is active")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Isle overlay",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps the floating Isle overlay running"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    // Window creation
    // ------------------------------------------------------------------

    private fun createWindow() {
        iconView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            // Transparent tile: the shape still rounds/clips the icon, but
            // sender icons / album art / our own black logo sit directly on
            // the black pill with no visible box.
            background = roundedRectBackground(0x00000000, dp(8))
        }

        eqBars = (0 until 3).map {
            View(this).apply {
                setBackgroundColor(0xFF7ED6DF.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(4)).apply {
                    marginStart = if (it == 0) 0 else dp(3)
                }
            }
        }
        eqContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginStart = dp(6)
            }
            visibility = View.GONE
            eqBars.forEach { addView(it) }
        }

        appLabelView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF9E9EA7.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bodyView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            // A few wrapped lines, then ellipsize — compact, iPhone-like.
            maxLines = MAX_BODY_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        timestampView = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF6E6E76.toInt())
            maxLines = 1
            gravity = Gravity.END
            visibility = View.GONE
        }

        labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(
                appLabelView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(timestampView)
        }

        textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            addView(labelRow)
            addView(bodyView)
            // mediaProgressBar added in Task 2
        }

        btnPrev = transportButton(R.drawable.ic_prev) {
            DynamicIsland.mediaState?.controller?.transportControls?.skipToPrevious()
        }
        btnPlay = transportButton(R.drawable.ic_play) {
            val c = DynamicIsland.mediaState?.controller ?: return@transportButton
            if (DynamicIsland.mediaState?.playing == true) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }
        btnNext = transportButton(R.drawable.ic_next) {
            DynamicIsland.mediaState?.controller?.transportControls?.skipToNext()
        }
        controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            addView(btnPrev)
            addView(btnPlay)
            addView(btnNext)
        }

        // Unlock flourish glyph (pulsing open-lock, Face-ID-dots spirit).
        unlockView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.ic_unlock)
            imageTintList = ColorStateList.valueOf(0xFF7ED6DF.toInt())
            visibility = View.GONE
        }

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            // Pure black so the pill is invisible against the punch-hole
            // camera when idle and reads as "the camera got wider" on alerts.
            background = roundedRectBackground(0xFF000000.toInt(), dp(22))
            setPadding(dp(10), dp(0), dp(6), dp(0))
            addView(iconView)
            addView(eqContainer)
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                // gap between icon and text
                marginStart = dp(8)
            })
            addView(controlsRow)
            addView(unlockView)
            setOnClickListener {
                when (mode) {
                    Mode.MEDIA, Mode.CALL, Mode.TIMER -> {
                        userExpanded = !userExpanded
                        render()
                    }
                    Mode.FLASH -> when {
                        !flashExpanded -> {
                            // Compact: skip wait, expand immediately
                            main.removeCallbacks(flashExpandRunnable)
                            main.removeCallbacks(flashArmedRunnable)
                            flashExpanded = true
                            flashArmed = false
                            main.postDelayed(flashArmedRunnable, EXPAND_MS)
                            render()
                        }
                        flashArmed -> {
                            // Expanded + armed: open source app
                            currentAlert?.let { a -> DynamicIsland.openAlert(a.key) }
                        }
                        // expanded but not yet armed — ignore
                    }
                    else -> Unit
                }
            }
            // Long-press removed: tap now handles open-app
        }

        // iPhone-style idle capsule: just large enough to wrap the front
        // camera cutout. It is empty (black) when idle — content only
        // appears while a mode is active.
        compactWidthPx = dp(60)
        compactHeightPx = dp(34)
        expandedWidthPx = computeExpandedWidth()

        windowParams = WindowManager.LayoutParams(
            compactWidthPx,
            compactHeightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = provisionalTopY()
            // Let the island draw into the camera-cutout area and receive the
            // cutout's bounding rects so it can center itself on the camera.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = cutoutModeForSdk()
            }
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = rootView!!
        windowManager?.addView(root, windowParams)

        // Position the island in place of the front camera, like on iPhone:
        // horizontally centered, vertically centered on the camera cutout.
        // Re-applied after rotation / cutout changes.
        root.setOnApplyWindowInsetsListener { _, insets ->
            windowParams?.let {
                it.y = islandTopY(insets)
                windowManager?.updateViewLayout(root, it)
            }
            insets
        }

        // Idle: a plain black capsule around the camera — no content.
        setContentAlpha(0f)
    }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun transportButton(iconRes: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    private fun roundedRectBackground(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    private fun computeExpandedWidth(): Int {
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.widthPixels
        }
        val desired = dp(300)
        return minOf(desired, metrics - dp(28))
    }

    /** Cutout mode allowing the island into the camera area (API-version safe). */
    private fun cutoutModeForSdk(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

    private fun statusBarHeightPx(): Int {
        val res = resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else dp(24)
    }

    /** Best-effort top inset from a WindowInsets (API-version safe). */
    @Suppress("DEPRECATION")
    private fun topInsetOf(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            ).top
        } else {
            insets.systemWindowInsetTop
        }

    /**
     * Initial Y before the first real insets arrive: center the compact pill
     * vertically in the status-bar band (the camera lives inside it).
     */
    private fun provisionalTopY(): Int =
        ((statusBarHeightPx() - compactHeightPx) / 2).coerceAtLeast(dp(0))

    /**
     * Y position that puts the island "in place of the camera", like iPhone:
     *
     *  1. Preferred: center the pill vertically on the front-camera cutout
     *     reported by the display cutout API (API 28+). The pill top stays
     *     pinned to this Y while it expands, so it grows downward from the
     *     camera exactly like the iPhone island.
     *  2. Fallback (no cutout info): center the pill vertically in the top
     *     inset band.
     *
     * The pill is always horizontally centered (the device's front camera is
     * centered; for a corner/edge punch-hole this line would need adjusting).
     */
    private fun islandTopY(insets: WindowInsets): Int {
        val params = windowParams
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && params != null) {
            val rects = insets.displayCutout?.boundingRects
            if (!rects.isNullOrEmpty()) {
                // Top-most cutout = the front camera. Rects are delivered in
                // this window's coordinate space.
                val cutout = rects.minByOrNull { it.top }
                if (cutout != null && cutout.height() in dp(6)..dp(64)) {
                    val holeCenterY = params.y + cutout.exactCenterY()
                    return (holeCenterY - compactHeightPx / 2f)
                        .roundToInt()
                        .coerceAtLeast(dp(0))
                }
            }
        }
        return ((topInsetOf(insets) - compactHeightPx) / 2).coerceAtLeast(dp(0))
    }

    // ------------------------------------------------------------------
    // Mode resolution & rendering (in-memory only, transient)
    // ------------------------------------------------------------------

    /** Thread-safe entry point used by [DynamicIsland.onLiveStateChanged]. */
    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { render() }
        } else {
            render()
        }
    }

    private fun Mode.isPersistent(): Boolean =
        this == Mode.MEDIA || this == Mode.CALL || this == Mode.TIMER

    /**
     * A flash interrupts any persistent mode; when it ends the pill reverts
     * to the highest-priority persistent state still active underneath.
     */
    private fun targetMode(): Mode = when {
        currentAlert != null -> Mode.FLASH
        unlockActive -> Mode.UNLOCK
        DynamicIsland.callStartedAtElapsedMs != null -> Mode.CALL
        DynamicIsland.timerState != null -> Mode.TIMER
        DynamicIsland.mediaState != null -> Mode.MEDIA
        else -> Mode.IDLE
    }

    /**
     * Called by [DynamicIsland.dispatchAlert] (listener thread) and from
     * [onStartCommand] (main thread). Safe from either.
     *
     * REPLACE vs QUEUE: when a new alert arrives while one is showing we
     * REPLACE it (smoothly refreshing in place) instead of queueing. Reasons:
     *  - the island mirrors the *now* of the notification shade — a queued
     *    stale alert that pops up 10 s late is surprising, not helpful;
     *  - bursts (chat threads, download spams) would otherwise keep the
     *    pill expanded for minutes;
     *  - the in-place refresh animates the height/content change, which
     *    reads as one fluid island, matching iOS behavior.
     */
    fun showAlert(alert: NotificationAlert) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { showAlert(alert) }
            return
        }
        rootView ?: return
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(flashArmedRunnable)
        main.removeCallbacks(flashExpandRunnable)
        cancelAnimations()
        currentAlert?.let { DynamicIsland.dropContentIntent(it.key) }
        currentAlert = alert
        flashArmed = false
        flashExpanded = false
        flashStartedAtMs = System.currentTimeMillis()

        render(fromFlashStart = true)   // compact pill appears immediately

        main.postDelayed(flashExpandRunnable, FLASH_COMPACT_HOLD_MS)
        main.postDelayed(flashArmedRunnable, FLASH_COMPACT_HOLD_MS + EXPAND_MS)

        val holdMs = HOLD_MS +
            (alert.text.length * HOLD_PER_CHAR_MS).coerceAtMost(MAX_EXTRA_HOLD_MS)
        main.postDelayed(hideRunnable, FLASH_COMPACT_HOLD_MS + EXPAND_MS + holdMs)
    }

    /** Notification dismissed (or removed) in the shade / by its app. */
    fun onAlertRemoved(key: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onAlertRemoved(key) }
            return
        }
        if (currentAlert?.key != key) return
        DynamicIsland.dropContentIntent(key)
        currentAlert = null
        flashArmed = false
        render()
    }

    /**
     * Collapse the flash right now (used after a tap-to-open). The tapped
     * alert's PendingIntent was already consumed by [DynamicIsland.openAlert].
     */
    fun endFlashNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { endFlashNow() }
            return
        }
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(flashArmedRunnable)
        main.removeCallbacks(flashExpandRunnable)
        flashArmed = false
        currentAlert = null
        render()
    }

    private fun render(fromFlashStart: Boolean = false) {
        val root = rootView ?: return
        val params = windowParams ?: return

        val newMode = targetMode()
        // Keep the user's expand/collapse choice for a persistent mode alive
        // across a flash interrupt; reset it only when the persistent mode
        // itself changes (e.g. media -> call).
        if (newMode.isPersistent()) {
            if (lastPersistent != newMode) userExpanded = false
            lastPersistent = newMode
        }
        val previous = mode
        mode = newMode

        // Target geometry + content per mode.
        val targetW: Int
        val targetH: Int
        // Default: flourish hidden; applyUnlockContent() re-shows it.
        unlockView.visibility = View.GONE
        stopUnlockPulse()
        when (mode) {
            Mode.IDLE -> {
                targetW = compactWidthPx
                targetH = compactHeightPx
            }
            Mode.UNLOCK -> {
                applyUnlockContent()
                targetW = compactWidthPx
                targetH = compactHeightPx
            }
            Mode.FLASH -> {
                applyFlashContent()
                if (flashExpanded) {
                    targetW = expandedWidthPx
                    targetH = computeFlashHeight()
                } else {
                    targetW = dp(112)
                    targetH = dp(44)
                }
            }
            Mode.MEDIA -> {
                applyMediaContent()
                if (userExpanded) {
                    targetW = expandedWidthPx
                    targetH = dp(76)
                } else {
                    targetW = dp(112)
                    targetH = dp(44)
                }
            }
            Mode.CALL -> {
                applyCallContent()
                if (userExpanded) {
                    targetW = dp(190)
                    targetH = dp(52)
                } else {
                    targetW = dp(118)
                    targetH = dp(40)
                }
            }
            Mode.TIMER -> {
                applyTimerContent()
                if (userExpanded) {
                    targetW = dp(190)
                    targetH = dp(52)
                } else {
                    targetW = dp(118)
                    targetH = dp(40)
                }
            }
        }

        // Touches pass through unless something is interactive: persistent
        // modes always, a flash only once expanded (armed).
        applyTouchability(
            mode.isPersistent() || mode == Mode.FLASH
        )

        // Live tickers & waveform follow the mode.
        updateTicker()
        updateEq()

        if (mode == Mode.IDLE) {
            // Collapse back into the camera and drop all content when done.
            animateShape(params.width, params.height, targetW, targetH,
                COLLAPSE_MS, easeCollapse)
            fadeContent(to = 0f, startDelayMs = 0L)
            return
        }

        val expanding = previous == Mode.IDLE || fromFlashStart ||
            targetH > params.height
        animateShape(params.width, params.height, targetW, targetH,
            EXPAND_MS, springExpand)
        if (previous == Mode.IDLE) {
            // Content fades in once the pill is wide enough (iPhone-like).
            setContentAlpha(0f)
            fadeContent(to = 1f, startDelayMs = EXPAND_MS * 3 / 5)
        } else if (expanding && previous != mode) {
            fadeContent(to = 1f, startDelayMs = 0L)
        } else {
            setContentAlpha(1f)
        }
    }

    // -- per-mode content ------------------------------------------------

    private fun setIconSize(sizePx: Int) {
        val lp = iconView.layoutParams
        lp.width = sizePx
        lp.height = sizePx
        iconView.layoutParams = lp
    }

    /** Pulsing open-lock glyph in the compact pill for ~UNLOCK_MS. */
    private fun applyUnlockContent() {
        iconView.visibility = View.GONE
        textColumn.visibility = View.GONE
        eqContainer.visibility = View.GONE
        controlsRow.visibility = View.GONE
        unlockView.visibility = View.VISIBLE
        startUnlockPulse()
    }

    private fun applyFlashContent() {
        val alert = currentAlert ?: return
        iconView.visibility = View.VISIBLE
        iconView.scaleType = ImageView.ScaleType.CENTER_CROP
        iconView.imageTintList = null
        iconView.setImageDrawable(alert.icon)
        eqContainer.visibility = View.GONE
        controlsRow.visibility = View.GONE

        if (flashExpanded) {
            setIconSize(dp(36))
            textColumn.visibility = View.VISIBLE
            labelRow.visibility = View.VISIBLE
            appLabelView.text = alert.appLabel
            val elapsedSec = (System.currentTimeMillis() - flashStartedAtMs) / 1000L
            timestampView.text = if (elapsedSec < 5L) "just now" else "${elapsedSec}s ago"
            timestampView.visibility = View.VISIBLE
            bodyView.textSize = 13f
            bodyView.maxLines = MAX_BODY_LINES
            bodyView.text = alert.text.ifBlank { alert.title }
        } else {
            setIconSize(dp(28))
            textColumn.visibility = View.GONE
            timestampView.visibility = View.GONE
        }
    }

    private fun applyMediaContent() {
        val media = DynamicIsland.mediaState ?: return
        iconView.visibility = View.VISIBLE
        if (userExpanded) {
            setIconSize(dp(48))
            textColumn.visibility = View.VISIBLE
            appLabelView.visibility = View.VISIBLE
            appLabelView.text = media.artist.ifBlank { media.appLabel }
            bodyView.text = media.title.ifBlank { media.appLabel }
            bodyView.maxLines = 1
            controlsRow.visibility = View.VISIBLE
            btnPlay.setImageResource(
                if (media.playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        } else {
            setIconSize(dp(28))
            textColumn.visibility = View.GONE
            controlsRow.visibility = View.GONE
        }
        iconView.scaleType = ImageView.ScaleType.CENTER_CROP
        iconView.imageTintList = null
        if (media.art != null) {
            iconView.setImageBitmap(media.art)
        } else {
            iconView.setImageResource(R.drawable.ic_music)
            iconView.imageTintList = ColorStateList.valueOf(0xFF9E9EA7.toInt())
            iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
    }

    private fun applyCallContent() {
        iconView.visibility = View.VISIBLE
        setIconSize(if (userExpanded) dp(22) else dp(18))
        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iconView.imageTintList = ColorStateList.valueOf(0xFF30D158.toInt())
        iconView.setImageResource(R.drawable.ic_call)
        textColumn.visibility = View.VISIBLE
        appLabelView.visibility = if (userExpanded) View.VISIBLE else View.GONE
        appLabelView.text = "Call"
        val start = DynamicIsland.callStartedAtElapsedMs
        bodyView.maxLines = 1
        bodyView.text = if (start != null) {
            formatElapsed(SystemClock.elapsedRealtime() - start)
        } else {
            ""
        }
        eqContainer.visibility = View.GONE
        controlsRow.visibility = View.GONE
    }

    private fun applyTimerContent() {
        iconView.visibility = View.VISIBLE
        setIconSize(if (userExpanded) dp(22) else dp(18))
        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iconView.imageTintList = ColorStateList.valueOf(0xFFFFD60A.toInt())
        iconView.setImageResource(R.drawable.ic_timer)
        textColumn.visibility = View.VISIBLE
        appLabelView.visibility = if (userExpanded) View.VISIBLE else View.GONE
        appLabelView.text =
            if (DynamicIsland.timerState?.kind == TimerKind.STOPWATCH) "Stopwatch" else "Timer"
        bodyView.maxLines = 1
        bodyView.text = timerReadout()
        eqContainer.visibility = View.GONE
        controlsRow.visibility = View.GONE
    }

    private fun timerReadout(): String {
        val t = DynamicIsland.timerState ?: return ""
        val elapsed = SystemClock.elapsedRealtime() - t.startedAtElapsedMs
        return if (t.kind == TimerKind.COUNTDOWN) {
            formatElapsed(t.durationMs - elapsed)
        } else {
            formatElapsed(elapsed)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    // -- tickers & waveform ----------------------------------------------

    private fun updateTicker() {
        val need = mode == Mode.CALL || mode == Mode.TIMER
        if (need && !tickerOn) {
            tickerOn = true
            tickOnce()
            main.postDelayed(tickRunnable, TICK_MS)
        } else if (!need && tickerOn) {
            tickerOn = false
            main.removeCallbacks(tickRunnable)
        }
    }

    private fun tickOnce() {
        when (mode) {
            Mode.CALL -> {
                val start = DynamicIsland.callStartedAtElapsedMs ?: return
                bodyView.text = formatElapsed(SystemClock.elapsedRealtime() - start)
            }
            Mode.TIMER -> {
                val t = DynamicIsland.timerState ?: return
                if (t.kind == TimerKind.COUNTDOWN) {
                    val remaining =
                        t.durationMs - (SystemClock.elapsedRealtime() - t.startedAtElapsedMs)
                    if (remaining <= 0) {
                        DynamicIsland.stopTimer() // triggers refresh()
                        return
                    }
                    bodyView.text = formatElapsed(remaining)
                } else {
                    bodyView.text =
                        formatElapsed(SystemClock.elapsedRealtime() - t.startedAtElapsedMs)
                }
            }
            else -> Unit
        }
    }

    private fun updateEq() {
        val show = mode == Mode.MEDIA && DynamicIsland.mediaState?.playing == true
        eqContainer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) startEq() else stopEq()
    }

    private fun startEq() {
        if (eqAnimator != null) return
        eqAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { a ->
                val t = (a.animatedValue as Float).toDouble()
                eqBars.forEachIndexed { i, bar ->
                    val phase = t * 2 * PI + i * 2.1
                    val frac = 0.3 + 0.7 * abs(sin(phase))
                    val lp = bar.layoutParams
                    lp.height = (dp(18) * frac).toInt().coerceAtLeast(dp(3))
                    bar.layoutParams = lp
                }
            }
        }.also { it.start() }
    }

    private fun stopEq() {
        eqAnimator?.cancel()
        eqAnimator = null
        eqBars.forEach { bar ->
            val lp = bar.layoutParams
            lp.height = dp(4)
            bar.layoutParams = lp
        }
    }

    /** Gentle scale pulse for the unlock glyph (iPhone-unlock spirit). */
    private fun startUnlockPulse() {
        if (unlockPulse != null) return
        unlockPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { a ->
                val t = ((a.animatedValue as Float).toDouble() * PI)
                val scale = (0.85 + 0.25 * abs(sin(t))).toFloat()
                unlockView.scaleX = scale
                unlockView.scaleY = scale
            }
        }.also { it.start() }
    }

    private fun stopUnlockPulse() {
        unlockPulse?.cancel()
        unlockPulse = null
        if (::unlockView.isInitialized) {
            unlockView.scaleX = 1f
            unlockView.scaleY = 1f
        }
    }

    // -- window plumbing ---------------------------------------------------

    private fun applyTouchability(touchable: Boolean) {
        val params = windowParams ?: return
        val root = rootView ?: return
        val newFlags = if (touchable) baseFlags()
        else baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (params.flags == newFlags) return
        params.flags = newFlags
        val wm = windowManager ?: return
        // Some OEM skins silently ignore flag changes made through
        // updateViewLayout, leaving the pill permanently untouchable
        // ("taps do nothing" on certain devices). Re-adding the window is
        // the reliable path: flags are guaranteed to be re-read at addView.
        try {
            wm.removeViewImmediate(root)
        } catch (_: Exception) {
            // already detached
        }
        try {
            wm.addView(root, params)
        } catch (_: Exception) {
            // window manager gone (service stopping)
        }
    }

    /**
     * Height that fits the icon row or the full multi-line flash body,
     * whichever is taller (never below the classic 56 dp expanded pill, never
     * above [MAX_EXPANDED_HEIGHT_DP] so the island can't swallow the screen).
     */
    private fun computeFlashHeight(): Int {
        val contentWidth =
            (expandedWidthPx - dp(20) - dp(32) - dp(8)).coerceAtLeast(dp(40))
        val wSpec =
            View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
        val hSpec =
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        appLabelView.measure(wSpec, hSpec)
        bodyView.measure(wSpec, hSpec)
        val contentHeight = appLabelView.measuredHeight + bodyView.measuredHeight
        return maxOf(
            dp(64),
            minOf(contentHeight + dp(16), dp(MAX_EXPANDED_HEIGHT_DP))
        )
    }

    private fun setContentAlpha(alpha: Float) {
        iconView.alpha = alpha
        textColumn.alpha = alpha
        eqContainer.alpha = alpha
        controlsRow.alpha = alpha
        unlockView.alpha = alpha
        timestampView.alpha = alpha
    }

    /** SECURITY: drop the in-memory content once it is no longer visible. */
    private fun clearContent() {
        currentAlert = null
        iconView.setImageDrawable(null)
        iconView.setImageBitmap(null)
        appLabelView.text = ""
        bodyView.text = ""
        setContentAlpha(0f)
        eqContainer.visibility = View.GONE
        controlsRow.visibility = View.GONE
        unlockView.visibility = View.GONE
        stopEq()
        stopUnlockPulse()
    }

    // ------------------------------------------------------------------
    // Animation helpers
    // ------------------------------------------------------------------

    private fun animateShape(
        fromW: Int,
        fromH: Int,
        toW: Int,
        toH: Int,
        durationMs: Long,
        interpolator: TimeInterpolator
    ) {
        val root = rootView ?: return
        val params = windowParams ?: return
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.interpolator = interpolator
            duration = durationMs
            addUpdateListener {
                val t = it.animatedValue as Float
                params.width = (fromW + (toW - fromW) * t).toInt()
                params.height = (fromH + (toH - fromH) * t).toInt()
                try {
                    windowManager?.updateViewLayout(root, params)
                } catch (_: Exception) {
                    // window already gone; nothing to do
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Only the animator that currently owns the pill may
                    // finalize the window size. A cancelled or superseded
                    // animator (a newer alert/animation replaced this one)
                    // must not touch the layout or trigger idle cleanup.
                    if (shapeAnimator !== animation) return
                    shapeAnimator = null
                    params.width = toW
                    params.height = toH
                    try {
                        windowManager?.updateViewLayout(root, params)
                    } catch (_: Exception) {
                        // window already gone; nothing to do
                    }
                    if (mode == Mode.IDLE) clearContent()
                }
            })
        }
        shapeAnimator = animator
        animator.start()
    }

    private fun fadeContent(to: Float, startDelayMs: Long) {
        val set = AnimatorSet()
        set.playTogether(
            ObjectAnimator.ofFloat(iconView, "alpha", iconView.alpha, to),
            ObjectAnimator.ofFloat(textColumn, "alpha", textColumn.alpha, to),
            ObjectAnimator.ofFloat(eqContainer, "alpha", eqContainer.alpha, to),
            ObjectAnimator.ofFloat(controlsRow, "alpha", controlsRow.alpha, to),
            ObjectAnimator.ofFloat(unlockView, "alpha", unlockView.alpha, to),
            ObjectAnimator.ofFloat(timestampView, "alpha", timestampView.alpha, to)
        )
        set.duration = 150L
        set.startDelay = startDelayMs
        fadeAnimator = set
        set.start()
    }

    private fun cancelAnimations() {
        shapeAnimator?.cancel()
        shapeAnimator = null
        fadeAnimator?.cancel()
        fadeAnimator = null
    }

    private fun removeWindow() {
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
            // view already removed
        }
        rootView = null
        windowManager = null
        windowParams = null
    }
}
