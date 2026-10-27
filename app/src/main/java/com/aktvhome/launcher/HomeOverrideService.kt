package com.aktvhome.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aktvhome.launcher.data.AccessibilityGuard
import com.aktvhome.launcher.data.DeviceCapabilities
import com.aktvhome.launcher.data.NbaAlerts
import com.aktvhome.launcher.data.SettingsStore
import com.aktvhome.launcher.ui.AlertBanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Home button override. Google TV always opens its own launcher on Home, whatever the default is
 * set to, so this:
 * - catches the Home key itself and opens Haven instead (works even when Google TV's home is
 *   already on screen, where no window change happens);
 * - swaps the device's own home page for Haven whenever it comes up some other way (Back out of an
 *   app): Google TV's, the classic Android TV launcher's or a maker's own, whichever this device has;
 * - opens Haven when the service starts and that home is already showing (boot, updates).
 * On TVs without Sony's control service it also puts the device to sleep and opens Android's power
 * menu for Quick settings' power button and the sleep timer ([sleep], [powerMenu]).
 * It only looks at which app is in front, not at what's on screen. Two exceptions, each only while
 * Haven is waiting for it:
 * - pairing with the TV: Sony's registration popup covers Haven and cancels the PIN when it closes,
 *   so Haven reads the PIN from that popup ([readSonyPin]);
 * - one-press Bluetooth speakers: after Haven opens the TV's page for a speaker, it presses that
 *   page's Connect or Disconnect ([pressInTvSettings]), then goes Back to Haven. Only in the TV's
 *   Settings app, only that one button, and only for 10 s after Haven asks.
 * It also hosts NBA alerts ([NbaAlerts]), since it runs whatever app is in front: it polls ESPN and
 * draws the banners ([AlertBanner]) as an accessibility overlay. Reading what's playing (to skip
 * the game you're watching) goes through media sessions, not the screen.
 */
class HomeOverrideService : AccessibilityService() {

    private var lastRedirect = 0L

    /** The device's own home screen activity, watched so Haven opens instead. */
    private var home: ComponentName? = null
    private val homePackage: String get() = home?.packageName ?: GOOGLE_TV_LAUNCHER

    private val guard = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var banner: AlertBanner? = null
    private var alerts: NbaAlerts? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        // The device's own home screen (Google TV, the classic Android TV launcher or a maker's own).
        home = DeviceCapabilities.stockLauncher(applicationContext)
        if (rootInActiveWindow?.packageName == homePackage) openHaven()
        val store = SettingsStore(applicationContext)
        val b = AlertBanner(this, store) { service, answer ->
            guard.launch { runCatching { AccessibilityGuard.answer(applicationContext, service, answer) } }
        }
        banner = b
        alerts = NbaAlerts(applicationContext, guard, store, foreground = { rootInActiveWindow?.packageName?.toString() }, show = b::show)
            .also { it.start() }
        // This service runs from internal storage for as long as the TV is on, so it also restores
        // other apps' accessibility services once their USB-stored apps load: every 20 s for the
        // first 10 minutes, then every 2 minutes, and straight away whenever the TV's list changes
        // (so a service switched off in the TV's settings is asked about at once).
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11yObserver)
        guard.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                guardServices()
                delay(if (System.currentTimeMillis() - start < 10 * 60_000L) 20_000L else 120_000L)
            }
        }
    }

    private val a11yObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            guard.launch { guardServices() }
        }
    }

    /** One watchdog check: restores dropped services and asks about ones switched off outside Haven. */
    private fun guardServices() {
        runCatching {
            val ask = AccessibilityGuard.check(applicationContext)
            AccessibilityGuard.settle(applicationContext)
            ask.forEach { banner?.ask(it, AccessibilityGuard.label(applicationContext, it)) }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        banner?.dismissAll()
        // Act on release; swallow the press too so Google TV's home never flashes up.
        if (event.action == KeyEvent.ACTION_UP) return openHaven()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != homePackage) return
        // Only the home page itself; the launcher's other screens (profiles, accounts, inputs) stay
        // usable. Google TV's home page is its HomeActivity; other launchers' is their Home activity.
        val cls = event.className?.toString() ?: return
        if (!cls.endsWith(".home.HomeActivity") && cls != home?.className) return
        openHaven()
    }

    /** Returns false if Haven couldn't be opened, so the Home key falls through to Google TV. */
    private fun openHaven(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRedirect < 700) return true
        lastRedirect = now
        return runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setClass(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * The 4-digit number on Sony's "Device registration requested" popup for Haven, or null if that
     * popup isn't showing. Looks only at a window whose text names that request and Haven.
     */
    private fun findSonyPin(): String? {
        val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            val texts = mutableListOf<String>()
            collectText(root, texts, depth = 0)
            val all = texts.joinToString(" ")
            if (!all.contains("registration", ignoreCase = true) || !all.contains("Haven")) continue
            texts.firstOrNull { PIN.matches(it.trim()) }?.let { return it.trim() }
        }
        return null
    }

    /**
     * Waits for the TV's Settings page for the armed device, presses its [Armed.action] button, then
     * goes Back to Haven. Gives up after 5 s on that page (or when the request expires), leaving the
     * page open so you can press the button yourself.
     */
    private suspend fun pressArmed(armed: Armed) {
        var seenAt = 0L
        while (System.currentTimeMillis() < armed.expiresAt) {
            val now = System.currentTimeMillis()
            if (seenAt != 0L && now - seenAt > 5_000) break
            val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(rootInActiveWindow)
            val pages = roots.filter { it.packageName == TV_SETTINGS && showsText(it, armed.device) }
            if (pages.isNotEmpty() && seenAt == 0L) seenAt = now
            val button = pages.firstNotNullOfOrNull { findText(it, armed.action, depth = 0) }
            if (button != null && clickable(button)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                delay(1_500)
                performGlobalAction(GLOBAL_ACTION_BACK)
                break
            }
            delay(300)
        }
        if (pending === armed) pending = null
    }

    private fun showsText(root: AccessibilityNodeInfo, text: String): Boolean {
        if (text.isBlank()) return true
        val texts = mutableListOf<String>()
        collectText(root, texts, depth = 0)
        return texts.any { it.contains(text.trim(), ignoreCase = true) }
    }

    private fun findText(node: AccessibilityNodeInfo, text: String, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.text?.toString()?.trim()?.equals(text, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { c -> findText(c, text, depth + 1)?.let { return it } }
        return null
    }

    /** [node] itself if it's clickable, otherwise its nearest clickable parent. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops++ < 8) {
            if (n.isClickable) return n
            n = n.parent
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 30 || out.size > 200) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, out, depth + 1) }
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(a11yObserver) }
        alerts?.stop()
        banner?.destroy()
        guard.cancel()
        connected = false
        instance = null
        pending = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    /** A button Haven wants pressed on the TV's page for [device], until [expiresAt]. */
    private class Armed(val action: String, val device: String, val expiresAt: Long)

    companion object {
        const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
        private const val TV_SETTINGS = "com.android.tv.settings"

        /**
         * True once Android has actually connected this service in the current process. After an
         * app update Android can keep the service listed as enabled without reconnecting it, so
         * Haven checks this and reconnects (see [com.aktvhome.launcher.data.HomeOverride.reconnectIfStale]).
         */
        @Volatile var connected = false

        @Volatile private var instance: HomeOverrideService? = null
        private val PIN = Regex("^\\d{4}$")

        /** True when the service is running, so Haven can read Sony's pairing PIN. */
        val canReadPin: Boolean get() = instance != null

        /** Shows a sample NBA alert in 10 s. False when the service isn't running. */
        fun sendTestAlert(): Boolean {
            val a = instance?.alerts ?: return false
            a.sendTest()
            return true
        }

        /** Puts the device to sleep, as its power button would. False when the service isn't running. */
        fun sleep(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true

        /** Opens Android's own power menu (Restart lives there). False when the service isn't running. */
        fun powerMenu(): Boolean = instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) == true

        /** Reads the PIN from Sony's registration popup, if it's on screen. */
        fun readSonyPin(): String? = instance?.findSonyPin()

        @Volatile private var pending: Armed? = null

        /**
         * Presses [action] ("Connect" or "Disconnect") on the TV's Settings page for [device] once
         * Haven opens it, then goes Back. Call just before opening the page. Returns false if the
         * service isn't running, so you press it yourself.
         */
        fun pressInTvSettings(action: String, device: String): Boolean {
            val service = instance ?: return false
            val armed = Armed(action, device, System.currentTimeMillis() + 10_000)
            pending = armed
            service.guard.launch { service.pressArmed(armed) }
            return true
        }
    }
}
