package com.aktvhome.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aktvhome.launcher.recent.RecentMediaCollector

/**
 * Existing user-authorized notification listener: NBA/Now Playing also rely on its grant.
 * When Recent Apps preview capture is opted in, a notification can trigger a bounded
 * MediaSession recheck. No notification bodies are read or saved.
 */
class HavenMediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        RecentMediaCollector.onNotificationSignal(applicationContext, null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        RecentMediaCollector.onNotificationSignal(applicationContext, sbn?.packageName)
    }
}
