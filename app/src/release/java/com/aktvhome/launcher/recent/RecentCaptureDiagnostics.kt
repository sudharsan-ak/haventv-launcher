package com.aktvhome.launcher.recent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Release counterpart: manual diagnostic capture implementation is omitted. */
internal object RecentCaptureDiagnostics {
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) = Unit
    fun onServiceStopped() = Unit
}
