package com.aktvhome.launcher

import android.service.notification.NotificationListenerService

/**
 * Does nothing itself. Android only lets apps with Notification access read which media sessions
 * are playing, which NBA alerts need to skip the game you're watching. Granted once over ADB.
 */
class HavenMediaListener : NotificationListenerService()
