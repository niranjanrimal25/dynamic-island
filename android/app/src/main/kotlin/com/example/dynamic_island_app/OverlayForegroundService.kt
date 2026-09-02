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
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Foreground service that owns the floating "island" window.
 *
 * SECURITY:
 *  - Renders the *current* in-memory [NotificationAlert] only for the few
 *    seconds it is on screen, then clears the TextViews / ImageView.
 *  - NEVER writes notification content anywhere; the only disk state in the
 *    app is the user's ON/OFF preference (see [DynamicIsland]).
 *  - The persistent service notification shows a generic label only — never
 *    any captured content or icon.
 *  - Nothing here touches the network; the manifest has no INTERNET.
 */
class OverlayForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "dynamic_island_service"
        private const val NOTIFICATION_ID = 9001

        // Time budget: expand 220 ms, show ~3.4 s, collapse 260 ms.
        private const val EXPAND_MS = 220L
        private const val HOLD_MS = 3400L
        private const val COLLAPSE_MS = 260L

        /** Non-null while this service is alive (guarded by @Volatile). */
        @Volatile
        var instance: OverlayForegroundService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private lateinit var iconView: ImageView
    private lateinit var appLabelView: TextView
    private lateinit var bodyView: TextView
    private lateinit var textColumn: LinearLayout

    private var expandedWidthPx = 0
    private var compactWidthPx = 0
    private var compactHeightPx = 0
    private var expandedHeightPx = 0

    private var isExpanded = false

    private var currentAlert: NotificationAlert? = null
    private var shapeAnimator: ValueAnimator? = null
    private var fadeAnimator: AnimatorSet? = null
    private val hideRunnable = Runnable { collapseToIdle() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        createWindow()
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
            .setContentText("Dynamic Island is active")
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
            "Dynamic Island overlay",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps the floating Dynamic Island running"
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
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            background = roundedRectBackground(0xFF1F1F28.toInt(), dp(10))
        }

        appLabelView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF9E9EA7.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bodyView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            addView(appLabelView)
            addView(bodyView)
        }

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            background = roundedRectBackground(0xFF0B0B0F.toInt(), dp(22))
            setPadding(dp(12), dp(0), dp(12), dp(0))
            addView(iconView)
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                // gap between icon and text
                marginStart = dp(8)
            })
        }

        compactWidthPx = dp(64)
        compactHeightPx = dp(40)
        expandedHeightPx = dp(56)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = verticalTopInsetPx()
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = rootView!!
        windowManager?.addView(root, windowParams)

        // Re-position after rotation / cutout changes.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insetTopOf(insets)
            windowParams?.let {
                it.y = top
                windowManager?.updateViewLayout(root, it)
            }
            insets
        }

        textColumn.alpha = 0f // idle compact pill shows only the icon
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
        val desired = dp(320)
        return minOf(desired, metrics - dp(16))
    }

    private fun verticalTopInsetPx(): Int {
        // Best effort: status bar height (framework resource) + small margin.
        val res = resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) res.getDimensionPixelSize(id) else dp(24)
        return statusBar + dp(8)
    }

    private fun insetTopOf(insets: WindowInsets): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val systemBars = insets.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            if (systemBars.top > 0) return systemBars.top + dp(8)
        }
        return verticalTopInsetPx()
    }

    // ------------------------------------------------------------------
    // Alert rendering (in-memory only, transient)
    // ------------------------------------------------------------------

    /**
     * Called by [DynamicIsland.dispatchAlert] (listener thread) and from
     * [onStartCommand] (main thread). Safe from either.
     */
    fun showAlert(alert: NotificationAlert) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { showAlert(alert) }
            return
        }
        val root = rootView ?: return

        // Replace whatever is displayed: this alert becomes current.
        currentAlert = alert
        main.removeCallbacks(hideRunnable)
        cancelAnimations()

        iconView.setImageDrawable(alert.icon)
        appLabelView.text = alert.appLabel
        bodyView.text = alert.text.ifBlank { alert.title }

        val params = windowParams ?: return
        if (!isExpanded) {
            // Expand pill to content width/height.
            textColumn.alpha = 0f
            animateShape(
                fromW = params.width,
                fromH = params.height,
                toW = expandedWidthPx,
                toH = expandedHeightPx,
                durationMs = EXPAND_MS,
                interpolator = DecelerateInterpolator()
            )
            fadeTextColumn(to = 1f, durationMs = 150L)
            isExpanded = true
        } else {
            // Already expanded: just refresh content in place.
            windowManager?.updateViewLayout(root, params)
        }

        main.postDelayed(hideRunnable, EXPAND_MS + HOLD_MS)
    }

    /** Notification dismissed (or removed) in the shade / by its app. */
    fun onAlertRemoved(key: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onAlertRemoved(key) }
            return
        }
        if (currentAlert?.key != key) return
        currentAlert = null
        if (isExpanded) collapseToIdle()
    }

    private fun collapseToIdle() {
        main.removeCallbacks(hideRunnable)
        cancelAnimations()

        val params = windowParams ?: return
        if (!isExpanded) {
            clearContent()
            return
        }
        animateShape(
            fromW = params.width,
            fromH = params.height,
            toW = compactWidthPx,
            toH = compactHeightPx,
            durationMs = COLLAPSE_MS,
            interpolator = AccelerateInterpolator()
        )
        fadeTextColumn(to = 0f, durationMs = 140L)
        isExpanded = false
    }

    /** Called once the collapse finishes and on idle starts. */
    private fun onIdle() {
        isExpanded = false
        clearContent()
    }

    /** SECURITY: drop the in-memory content once it is no longer visible. */
    private fun clearContent() {
        currentAlert = null
        iconView.setImageDrawable(null)
        appLabelView.text = ""
        bodyView.text = ""
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
                windowManager?.updateViewLayout(root, params)
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
                    if (!isExpanded) onIdle()
                }
            })
        }
        shapeAnimator = animator
        animator.start()
    }

    private fun fadeTextColumn(to: Float, durationMs: Long) {
        val set = AnimatorSet()
        set.playTogether(
            ObjectAnimator.ofFloat(textColumn, "alpha", textColumn.alpha, to)
        )
        set.duration = durationMs
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
