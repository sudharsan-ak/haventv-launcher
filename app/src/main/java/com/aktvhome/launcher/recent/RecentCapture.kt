package com.aktvhome.launcher.recent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File

/**
 * One-shot Accessibility screenshot experiment for Android 12.
 * Only runs after the user presses "Capture & open" for a specific app.
 * Never captures continuously, and never bypasses protected video windows.
 * Its small JPEG is stored only in Haven's private cache.
 */
object RecentCapture {
    @Volatile private var armedPackage: String? = null
    private const val CAPTURE_FILE = "haven_recents_sample.jpg"
    private const val PREFS = "haven_recents_probe"

    fun arm(context: android.content.Context, pkg: String) {
        armedPackage = pkg
        File(context.cacheDir, CAPTURE_FILE).delete()
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("status", "Waiting to capture " + pkg).apply()
    }

    fun onWindowChanged(service: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != armedPackage) return
        armedPackage = null // one shot, even if more window events arrive
        Handler(Looper.getMainLooper()).postDelayed({
            if (service.rootInActiveWindow?.packageName?.toString() != pkg) {
                writeStatus(service, "Skipped: foreground changed before capture")
                return@postDelayed
            }
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            try {
                                val bmp = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                if (bmp == null || bmp.width <= 0) {
                                    writeStatus(service, "Screenshot buffer unavailable")
                                    return
                                }
                                val width = minOf(480, bmp.width)
                                val height = maxOf(1, bmp.height * width / bmp.width)
                                val small = Bitmap.createScaledBitmap(bmp, width, height, true)
                                File(service.cacheDir, CAPTURE_FILE).outputStream().use { out ->
                                    small.compress(Bitmap.CompressFormat.JPEG, 78, out)
                                }
                                if (small !== bmp) small.recycle()
                                writeStatus(service, "Captured " + pkg + " (protected content may be blank)")
                            } finally {
                                buffer.close()
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            writeStatus(service, "Accessibility screenshot failed: " + errorCode)
                        }
                    },
                )
            } catch (e: Exception) {
                writeStatus(service, "Accessibility screenshot error: " + e.javaClass.simpleName)
            }
        }, 700)
    }

    private fun writeStatus(context: android.content.Context, value: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("status", value).apply()
    }

    fun status(context: android.content.Context): String =
        context.getSharedPreferences(PREFS, 0).getString("status", "No screenshot test yet").orEmpty()

    fun imageFile(context: android.content.Context): File =
        File(context.cacheDir, CAPTURE_FILE)
}
