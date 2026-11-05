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
 * Opt-in event-driven previews. All scheduling and service callbacks are on the
 * Accessibility main thread; bitmap conversion/encoding runs on one worker.
 * Never bypasses FLAG_SECURE or protected video.
 */
object RecentCapture {
    @Volatile private var armedPackage: String? = null
    private const val MANUAL_FILE = "haven_recents_sample.jpg"
    private const val PREFS = "haven_recents_probe"
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { action ->
        Thread(action, "HavenRecentPreview").apply { isDaemon = true }
    }
    private val lastAttempts = mutableMapOf<String, Long>()
    // Avoid PackageManager queries on every focus/scroll/content event.
    private val launchableCache = mutableMapOf<String, Boolean>()
    private var foreground: String? = null
    private var pending: Runnable? = null
    private var pendingPackage: String? = null
    private var inFlight = false
    private var lastGlobalAttempt = 0L

    fun arm(context: Context, pkg: String) {
        armedPackage = pkg
        File(context.cacheDir, MANUAL_FILE).delete()
        writeManual(context, "Waiting to capture $pkg")
    }

    fun resetAuto() {
        cancelPending()
        foreground = null
    }

    fun onServiceStopped() {
        resetAuto()
        armedPackage = null
    }

    /**
     * A best-effort screenshot request BEFORE Haven handles the Home press.
     * This does not wait for a screenshot and never delays navigation.
     * If Android delivers the buffer after switching apps it is discarded.
     */
    fun onHomeKeyDown(service: AccessibilityService) {
        if (!RecentPreviewStore.enabled(service)) return
        val pkg = service.rootInActiveWindow?.packageName?.toString() ?: return
        if (pkg != foreground || !eligible(service, pkg)) return
        cancelPending()
        capture(service, pkg, "Home press", manual = false)
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val window = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (window && pkg == armedPackage) {
            armedPackage = null
            handler.postDelayed({ capture(service, pkg, "Manual test", manual = true) }, 700L)
        }
        if (!RecentPreviewStore.enabled(service)) {
            if (pending != null) resetAuto()
            return
        }
        val navigation = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> true
            else -> false
        }
        if (!navigation) return
        if (!eligible(service, pkg)) {
            if (window) resetAuto()
            return
        }
        // Ignore notifications/background changes from a different process.
        if (service.rootInActiveWindow?.packageName?.toString() != pkg) return
        val reentered = pkg != foreground
        if (reentered) {
            foreground = pkg
            cancelPending()
        }
        if (pendingPackage == pkg || inFlight) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - (lastAttempts[pkg] ?: (now - 120_000L))
        val gap = if (reentered) RecentPreviewPolicy.REENTRY_GAP_MS
            else RecentPreviewPolicy.NAVIGATION_GAP_MS
        val settle = if (reentered) RecentPreviewPolicy.FIRST_CAPTURE_DELAY_MS
            else RecentPreviewPolicy.NAVIGATION_SETTLE_MS
        val delay = maxOf(settle, (gap - elapsed).coerceAtLeast(0L))
        val job = Runnable {
            pending = null
            pendingPackage = null
            if (RecentPreviewStore.enabled(service) && foreground == pkg &&
                service.rootInActiveWindow?.packageName?.toString() == pkg) {
                capture(service, pkg, if (reentered) "App foreground" else "Navigation",
                    manual = false)
            }
        }
        pending = job
        pendingPackage = pkg
        handler.postDelayed(job, delay)
    }

    private fun cancelPending() {
        pending?.let(handler::removeCallbacks)
        pending = null
        pendingPackage = null
    }

    private fun eligible(service: AccessibilityService, pkg: String): Boolean {
        if (pkg == service.packageName ||
            pkg == "com.android.systemui" ||
            pkg == "com.google.android.apps.tv.launcherx" ||
            pkg.startsWith("com.android.inputmethod") ||
            pkg.startsWith("com.google.android.inputmethod") ||
            pkg.startsWith("com.android.permissioncontroller")) return false
        launchableCache[pkg]?.let { return it }
        val launchable = runCatching {
            service.packageManager.getLeanbackLaunchIntentForPackage(pkg) != null ||
                service.packageManager.getLaunchIntentForPackage(pkg) != null
        }.getOrDefault(false)
        if (launchableCache.size >= 48) launchableCache.clear()
        launchableCache[pkg] = launchable
        return launchable
    }

    private fun capture(service: AccessibilityService, pkg: String, reason: String, manual: Boolean) {
        if (!manual && !RecentPreviewStore.enabled(service)) return
        if (service.rootInActiveWindow?.packageName?.toString() != pkg) {
            if (manual) writeManual(service, "Skipped: foreground changed")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (inFlight || now - lastGlobalAttempt < RecentPreviewPolicy.GLOBAL_GAP_MS) {
            if (manual) writeManual(service, "Screenshot already running / rate limited")
            return
        }
        inFlight = true
        lastGlobalAttempt = now
        lastAttempts[pkg] = now
        RecentPreviewStore.attempted(service, pkg, reason)
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        // A callback may arrive after Home opens; never cache Haven as the app.
                        if (service.rootInActiveWindow?.packageName?.toString() != pkg ||
                            (!manual && !RecentPreviewStore.enabled(service))) {
                            buffer.close()
                            RecentPreviewStore.note(service, pkg,
                                "Skipped: app switched before screenshot completed")
                            inFlight = false
                            return
                        }
                        worker.execute {
                            try {
                                val source = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    ?: throw IllegalStateException("Bitmap buffer unreadable")
                                if (source.width <= 0 || source.height <= 0) {
                                    throw IllegalStateException("Empty screenshot buffer")
                                }
                                val width = minOf(480, source.width)
                                val height = maxOf(1, source.height * width / source.width)
                                val scaled = Bitmap.createScaledBitmap(source, width, height, true)
                                val small = if (scaled.config == Bitmap.Config.HARDWARE)
                                    scaled.copy(Bitmap.Config.ARGB_8888, false)
                                else scaled
                                if (small == null) throw IllegalStateException("Bitmap conversion failed")
                                try {
                                    // Sampling happens off the Accessibility main thread.
                                    val pixels = (0 until 8).flatMap { row ->
                                        (0 until 12).map { col ->
                                            small.getPixel(col * (small.width - 1) / 11,
                                                row * (small.height - 1) / 7)
                                        }
                                    }
                                    if (RecentPreviewPolicy.isAlmostBlack(pixels)) {
                                        RecentPreviewStore.clearSecure(service, pkg)
                                        if (manual) writeManual(service, "Blank screenshot for $pkg")
                                    } else {
                                        val partial = RecentPreviewPolicy.hasLargeDarkRegion(pixels)
                                        val saved = RecentPreviewStore.save(service, pkg, small, partial)
                                        if (!saved) RecentPreviewStore.note(service, pkg,
                                            "Screenshot save failed; previous file retained")
                                        if (manual && saved) {
                                            File(service.cacheDir, MANUAL_FILE).outputStream().use {
                                                small.compress(Bitmap.CompressFormat.JPEG, 78, it)
                                            }
                                            writeManual(service, "Captured $pkg")
                                        }
                                    }
                                } finally {
                                    if (small !== source) small.recycle()
                                }
                            } catch (e: Exception) {
                                RecentPreviewStore.note(service, pkg,
                                    "Screenshot processing error: " + e.javaClass.simpleName)
                                if (manual) writeManual(service,
                                    "Screenshot processing failed: " + e.javaClass.simpleName)
                            } finally {
                                buffer.close()
                                handler.post { inFlight = false }
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reasonText = when (errorCode) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Secure window"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                                "Accessibility screenshot permission missing"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                "Android screenshot rate limit"
                            else -> "Screenshot error code $errorCode"
                        }
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                            RecentPreviewStore.clearSecure(service, pkg)
                        else RecentPreviewStore.note(service, pkg, reasonText)
                        if (manual) writeManual(service, reasonText)
                        inFlight = false
                    }
                })
        } catch (e: Exception) {
            RecentPreviewStore.note(service, pkg,
                "Screenshot API error: " + e.javaClass.simpleName)
            if (manual) writeManual(service, "Screenshot API error: " + e.javaClass.simpleName)
            inFlight = false
        }
    }

    private fun writeManual(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", text).apply()
    }

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("status", "No manual screenshot test yet").orEmpty()

    fun imageFile(context: Context): File = File(context.cacheDir, MANUAL_FILE)
}
