package com.aktvhome.launcher.recent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.util.concurrent.Executors

/**
 * Debug-only manual one-shot screenshot probe. Not included in release builds.
 * Automatic preview capture stays in RecentCapture and behaves independently.
 */
internal object RecentCaptureDiagnostics {
    private const val PREFS = "haven_recents_probe"
    private const val FILE = "haven_recents_sample.jpg"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HavenRecentManualProbe").apply { isDaemon = true }
    }
    @Volatile private var armedPackage: String? = null
    private var pending: Runnable? = null
    private var inFlight = false

    fun arm(context: Context, pkg: String) {
        armedPackage = pkg
        imageFile(context).delete()
        write(context, "Waiting to capture $pkg")
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != armedPackage) return
        armedPackage = null
        pending?.let(main::removeCallbacks)
        val task = Runnable { pending = null; capture(service, pkg) }
        pending = task
        main.postDelayed(task, 700L)
    }

    fun onServiceStopped() {
        armedPackage = null
        pending?.let(main::removeCallbacks)
        pending = null
    }

    private fun capture(service: AccessibilityService, pkg: String) {
        if (inFlight || service.rootInActiveWindow?.packageName?.toString() != pkg) {
            write(service, "Skipped: app switched or screenshot busy")
            return
        }
        inFlight = true
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            if (service.rootInActiveWindow?.packageName?.toString() != pkg) {
                                write(service, "Skipped: foreground changed during capture")
                                return
                            }
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                                write(service, "Screenshot buffer unreadable")
                                return
                            }
                            val w = minOf(bitmap.width, 480)
                            val h = maxOf(1, bitmap.height * w / bitmap.width)
                            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                            val small = if (scaled.config == Bitmap.Config.HARDWARE)
                                scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
                            if (small == null) {
                                write(service, "Screenshot bitmap conversion failed")
                                return
                            }
                            io.execute {
                                try {
                                    imageFile(service).outputStream().use { output ->
                                        check(small.compress(Bitmap.CompressFormat.JPEG, 78, output))
                                    }
                                    write(service, "Captured $pkg")
                                } catch (e: Exception) {
                                    write(service, "Manual capture save failed: " +
                                        e.javaClass.simpleName)
                                } finally {
                                    small.recycle()
                                }
                            }
                        } catch (e: Exception) {
                            write(service, "Screenshot error: " + e.javaClass.simpleName)
                        } finally {
                            buffer.close()
                            inFlight = false
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        write(service, "Android screenshot failure code $errorCode")
                        inFlight = false
                    }
                })
        } catch (e: Exception) {
            write(service, "Screenshot API error: " + e.javaClass.simpleName)
            inFlight = false
        }
    }

    private fun write(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", value).apply()
    }

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("status", "No manual screenshot test yet").orEmpty()

    fun imageFile(context: Context): File = File(context.cacheDir, FILE)
}
