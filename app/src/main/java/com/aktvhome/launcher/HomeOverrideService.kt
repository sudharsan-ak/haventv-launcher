package com.aktvhome.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Home button override. Google TV keeps its own launcher as the home screen whatever the
 * default is set to, so this watches for that launcher's home page coming up and opens Haven
 * instead. It reads nothing on screen: it only listens for the Google TV launcher's window.
 */
class HomeOverrideService : AccessibilityService() {

    private var lastRedirect = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != GOOGLE_TV_LAUNCHER) return
        // Only the home page itself; the launcher's other screens (profiles, accounts, inputs) stay usable.
        if (event.className?.toString()?.endsWith(".home.HomeActivity") != true) return
        val now = System.currentTimeMillis()
        if (now - lastRedirect < 700) return
        lastRedirect = now
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }

    override fun onInterrupt() = Unit

    companion object {
        const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
    }
}
