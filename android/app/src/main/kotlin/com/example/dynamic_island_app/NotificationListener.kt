package com.example.dynamic_island_app

import android.app.Notification
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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

    /**
     * Keys that WE cancelled in island-only mode. Cancelling a notification
     * makes the system deliver [onNotificationRemoved] for the same key;
     * without this guard that callback tore the island flash down a split
     * second after it appeared (the bug: flashes vanished instantly and
     * taps hit the pass-through idle capsule).
     */
    private val selfCancelled =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Never react to our own "overlay running" notification.
        if (sbn.packageName == packageName) return

        // Android (and most OEM skins) re-posts a system transparency notice
        // — "<app> is displaying over other apps…" — whenever an overlay app
        // becomes visible, e.g. after every unlock. The user asked for it
        // gone; since they granted us notification access we may dismiss
        // exactly that one notice and nothing else. Opt-outable in settings.
        if (DynamicIsland.isDismissOverlayWarningEnabled(this) &&
            isOverlayWarning(sbn)
        ) {
            cancelNotification(sbn.key)
            return
        }

        // Respect the OS-level "notification access" switch and the
        // per-channel switches the user controls in system Settings.
        if (!canReceiveNotifications()) return

        if (!DynamicIsland.isOverlayEnabled(this)) return

        // Ongoing notifications (a minimized music player, download bars,
        // other apps' foreground services) are always mirrored and kept in
        // the shade — they hold live controls. Regular notifications are
        // shown as the island flash and (island-only mode, default) then
        // removed from the shade.

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            .orEmpty()
        // Longest available body first: big text (the full email body for
        // Gmail), then inbox-style lines, then the regular text line.
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.map { it.toString().trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("\n")
                ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                ?.trim()
                .orEmpty()
        // Fold the title into the body when it adds information (email
        // sender/subject above the full text, track above artist, ...).
        val text = when {
            title.isEmpty() -> body
            body.isEmpty() -> title
            body == title -> body
            else -> "$title\n$body"
        }

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
            icon = extractIcon(pm, sbn.packageName),
            // What tapping the real notification would open; held in RAM
            // only, keyed by [key], for as long as the island shows it.
            contentIntent = sbn.notification.contentIntent
        )

        // Island-only mode: the notification exists ONLY as the island
        // flash — the system copy is cancelled so the shade never keeps it
        // (no shade history, no quick reply). Ongoing notifications (media
        // players, calls, downloads) are exempt: cancelling those would
        // strip playback/call controls and apps re-post them anyway.
        if (!sbn.isOngoing && DynamicIsland.isIslandOnlyEnabled(this)) {
            selfCancelled.add(sbn.key)
            if (selfCancelled.size > 500) selfCancelled.clear()
            cancelNotification(sbn.key)
        }

        if (IslandNotificationClassifier.isLiveActivity(sbn)) {
            DynamicIsland.dispatchAlert(this, alert)
        } else {
            DynamicIsland.dispatchBanner(this, alert)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?
    ) {
        // Removals caused by our own island-only dismissal are expected —
        // the island flash must live out its full hold time, not collapse
        // the moment the shade copy disappears.
        if (selfCancelled.remove(sbn.key)) return
        DynamicIsland.onNotificationRemoved(this, sbn.key)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * True only for the OS transparency notice about overlay apps
     * ("<app> is displaying over other apps…"). Matched by phrase so it
     * works across OEM skins; no other notification is ever touched.
     */
    private fun isOverlayWarning(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification.extras
        val haystack = buildString {
            append(extras.getCharSequence(Notification.EXTRA_TITLE) ?: "")
            append(' ')
            append(extras.getCharSequence(Notification.EXTRA_TEXT) ?: "")
        }.lowercase(java.util.Locale.ROOT)
        return haystack.contains("displaying over other apps") ||
            haystack.contains("displayed over other apps") ||
            haystack.contains("showing over other apps") ||
            haystack.contains("appearing over other apps")
    }

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
     * The sender app's launcher icon (the "app icon" from the requirements),
     * returned as a Drawable used only in memory by the overlay.
     *
     * Note: we deliberately do NOT read Notification.largeIcon here. That
     * path goes through Icon.loadDrawable(...), whose behavior/availability
     * varies across SDK levels; the launcher icon is the stable, requirement-
     * matching choice and keeps this file compiling on every compileSdk.
     */
    private fun extractIcon(
        pm: PackageManager,
        pkg: String
    ): Drawable? = runCatching {
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationIcon(appInfo)
    }.getOrNull()
}
