package com.example.dynamic_island_app

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * System notification listener (bound by the OS once the user grants
 * "Notification access" for this app in Android Settings).
 *
 * SECURITY MODEL — read carefully:
 *  - [onNotificationPosted] builds one in-memory [NotificationAlert] and hands
 *    it to [DynamicIsland.dispatchAlert]. From there it lives only in a
 *    Kotlin object / the overlay views for the seconds it is visible, and is
 *    cleared afterwards (see OverlayForegroundService.clearContent).
 *  - Nothing is written to disk, SharedPreferences, or the network. The app
 *    manifest has NO android.permission.INTERNET.
 *  - The title/text are only ever stored in [NotificationAlert] (RAM). They
 *    are never logged — the only log lines in this app are about service
 *    state, never about notification content.
 *  - The persistent service notification shows a generic label only.
 */
class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Never react to our own "overlay running" notification.
        if (sbn.packageName == packageName) return

        // Respect the OS-level "notification access" switch and the
        // per-channel switches the user controls in system Settings.
        if (!canReceiveNotifications()) return

        if (!DynamicIsland.isOverlayEnabled(this)) return

        // Skip ongoing/task-foreground notifications (media players, download
        // bars, other apps' foreground services). Delete this check if you
        // want the island to also reflect ongoing items.
        if (sbn.isOngoing) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            .orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                ?.trim()
                .orEmpty()

        if (title.isEmpty() && text.isEmpty()) return

        val pm = packageManager
        val appLabel = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrNull() ?: sbn.packageName

        val alert = NotificationAlert(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            icon = extractIcon(pm, sbn.notification, sbn.packageName)
        )

        DynamicIsland.dispatchAlert(this, alert)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?
    ) {
        DynamicIsland.onNotificationRemoved(this, sbn.key)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The OS only delivers callbacks to this service while "Notification
     * access" is enabled (that binding is the gate — Android also applies the
     * sender apps' per-channel switches before we see anything). Note this is
     * deliberately independent of whether the user allows *our app's* own
     * notifications: denying those hides the "overlay running" item but the
     * island itself keeps working.
     */
    private fun canReceiveNotifications(): Boolean =
        DynamicIsland.isNotificationAccessGranted(this)

    /**
     * Icon selection (Drawable used only in memory by the overlay):
     *  1. the sender's own "large icon" when it posts one (often an avatar /
     *     conversation photo), otherwise
     *  2. the app's launcher icon.
     */
    private fun extractIcon(
        pm: PackageManager,
        notification: Notification,
        pkg: String
    ): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val large = notification.largeIcon
            if (large != null) {
                val d = runCatching { large.loadDrawable(this) }.getOrNull()
                if (d != null) return d
            }
        }
        return runCatching {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationIcon(appInfo)
        }.getOrNull()
    }
}
