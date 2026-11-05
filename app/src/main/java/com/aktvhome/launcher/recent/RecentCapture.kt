package com.aktvhome.launcher.recent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.util.concurrent.Executors

/**
 * Reuses Haven's existing Accessibility service.
 * Automatic previews are opt-in, throttled, local and never bypass DRM/FLAG_SECURE.
 * The manual Commit 1 screenshot test remains available.
 */
object RecentCapture {
    @Volatile private var armedPackage: String? = null
    private const val CAPTURE_FILE = "haven_recents_sample.jpg"
    private const val PREFS = "haven_recents_probe"
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HavenRecentPreview").apply { isDaemon = true }
    }
    private val lastAttempts = mutableMapOf<String, Long>()
    private var foregroundPackage: String? = null
    private var pendingPackage: String? = null
    private var pending: Runnable? = null
    private var inFlight = false
    private var lastGlobalAttempt = 0L

    fun arm(context: Context, pkg: String) {
        armedPackage = pkg
        File(context.cacheDir, CAPTURE_FILE).delete()
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("status", "Waiting to capture " + pkg).apply()
    }

    fun resetAuto() {
        pending?.let(handler::removeCallbacks)
        pending = null
        pendingPackage = null
        foregroundPackage = null
    }

    fun onServiceStopped() {
        resetAuto()
        armedPackage = null
    }

    /** Runs only on the Accessibility service main thread. */
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val window = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (window && pkg == armedPackage) {
            armedPackage = null
            handler.postDelayed({ capture(service, pkg, manual = true) }, 700L)
        }

        if (!RecentPreviewStore.enabled(service)) {
            if (pending != null) resetAuto()
            return
        }
        if (!window && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        if (window && !eligible(service, pkg)) {
            resetAuto()
            return
        }
        if (!eligible(service, pkg)) return

        val reentered = window && pkg != foregroundPackage
        if (reentered) {
            foregroundPackage = pkg
            cancelPending()
        } else if (pkg != foregroundPackage) return

        // Content-change events can arrive many times a second; don't requeue the delay.
        if (pendingPackage == pkg || inFlight) return
        val elapsed = SystemClock.elapsedRealtime() - (lastAttempts[pkg] ?: -120_000L)
        if (!RecentPreviewPolicy.isDue(reentered, elapsed)) return
        val delayMs = if (reentered) RecentPreviewPolicy.FIRST_CAPTURE_DELAY_MS
            else RecentPreviewPolicy.REFRESH_DELAY_MS
        val job = Runnable {
            pending = null
            pendingPackage = null
            if (RecentPreviewStore.enabled(service) && foregroundPackage == pkg) {
                capture(service, pkg, manual = false)
            }
        }
        pending = job
        pendingPackage = pkg
        handler.postDelayed(job, delayMs)
    }

    private fun cancelPending() {
        pending?.let(handler::removeCallbacks)
        pending = null
        pendingPackage = null
    }

    private fun eligible(service: AccessibilityService, pkg: String): Boolean {
        if (pkg == service.packageName || pkg == "com.android.systemui" ||
            pkg == "com.google.android.apps.tv.launcherx" ||
            pkg.startsWith("com.android.inputmethod") ||
            pkg.startsWith("com.google.android.inputmethod") ||
            pkg.startsWith("com.android.permissioncontroller")) return false
        // Exclude Settings, system overlays and non-launchable windows.
        return runCatching {
            service.packageManager.getLeanbackLaunchIntentForPackage(pkg) != null ||
                service.packageManager.getLaunchIntentForPackage(pkg) != null
        }.getOrDefault(false)
    }

    private fun capture(service: AccessibilityService, pkg: String, manual: Boolean) {
        if (service.rootInActiveWindow?.packageName?.toString() != pkg) {
            if (manual) writeStatus(service, "Skipped: foreground changed before capture")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (inFlight || now - lastGlobalAttempt < 1_500L) {
            if (manual) writeStatus(service, "Capture already running, try again")
            return
        }
        inFlight = true
        lastGlobalAttempt = now
        lastAttempts[pkg] = now
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            if (service.rootInActiveWindow?.packageName?.toString() != pkg) {
                                if (manual) writeStatus(service, "Skipped: app switched during capture")
                                inFlight = false
                                return
                            }
                            val source = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            if (source == null || source.width <= 0 || source.height <= 0) {
                                report(service, pkg, manual, "No screenshot buffer")
                                inFlight = false
                                return
                            }
                            val width = minOf(480, source.width)
                            val height = maxOf(1, source.height * width / source.width)
                            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
                            val small = if (scaled.config == Bitmap.Config.HARDWARE)
                                scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
                            if (small == null) {
                                report(service, pkg, manual, "Could not decode preview")
                                inFlight = false
                                return
                            }
                            val samples = (0 until 6).flatMap { row ->
                                (0 until 10).map { col ->
                                    small.getPixel((col * (small.width - 1)) / 9,
                                        (row * (small.height - 1)) / 5)
                                }
                            }
                            if (RecentPreviewPolicy.isAlmostBlack(samples)) {
                                RecentPreviewStore.clearSecure(service, pkg)
                                report(service, pkg, manual, "Blank/near-black preview; use app artwork")
                                if (small !== source) small.recycle()
                                inFlight = false
                                return
                            }
                            worker.execute {
                                try {
                                    val saved = RecentPreviewStore.save(service, pkg, small)
                                    if (manual && saved) {
                                        File(service.cacheDir, CAPTURE_FILE).outputStream().use { out ->
                                            small.compress(Bitmap.CompressFormat.JPEG, 78, out)
                                        }
                                    }
                                    if (manual) {
                                        writeStatus(service, if (saved) "Captured " + pkg
                                            else "Unable to save preview for " + pkg)
                                    }
                                } catch (e: Exception) {
                                    report(service, pkg, manual, "Save error: " + e.javaClass.simpleName)
                                } finally {
                                    if (small !== source) small.recycle()
                                    handler.post { inFlight = false }
                                }
                            }
                        } catch (e: Exception) {
                            report(service, pkg, manual, "Screenshot error: " + e.javaClass.simpleName)
                            inFlight = false
                        } finally {
                            buffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = when (errorCode) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Secure window"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "No Accessibility screenshot access"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screenshot rate limited"
                            else -> "Android screenshot error " + errorCode
                        }
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                            RecentPreviewStore.clearSecure(service, pkg)
                        }
                        report(service, pkg, manual, reason)
                        inFlight = false
                    }
                },
            )
        } catch (e: Exception) {
            report(service, pkg, manual, "Screenshot exception: " + e.javaClass.simpleName)
            inFlight = false
        }
    }

    private fun report(service: AccessibilityService, pkg: String, manual: Boolean, reason: String) {
        RecentPreviewStore.note(service, pkg, reason)
        if (manual) writeStatus(service, reason + " (" + pkg + ")")
    }

    private fun writeStatus(context: Context, value: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("status", value).apply()
    }

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, 0).getString("status", "No manual screenshot test yet").orEmpty()

    fun imageFile(context: Context): File = File(context.cacheDir, CAPTURE_FILE)
}
