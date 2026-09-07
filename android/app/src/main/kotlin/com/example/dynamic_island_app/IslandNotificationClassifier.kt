package com.example.dynamic_island_app

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Decides whether an incoming notification belongs in the Dynamic Island
 * (live activity / system state) or the floating banner below it.
 *
 * Island-worthy: call, navigation, transport, ongoing service/progress.
 * Banner-worthy: message, email, social, event, promo, uncategorised non-ongoing.
 */
object IslandNotificationClassifier {

    fun isLiveActivity(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return when (n.category) {
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_TRANSPORT -> true
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS -> sbn.isOngoing
            else -> false
        }
    }
}
