package com.aktvhome.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aktvhome.launcher.data.AccessibilityGuard
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
 * - swaps Google TV's home page for Haven whenever it comes up some other way (Back out of an app);
 * - opens Haven when the service starts and Google TV's home is already showing (boot, updates).
 * It only looks at which app is in front, not at what's on screen. The one exception is pairing
 * with the TV: Sony's registration popup covers Haven and cancels the PIN when it closes, so Haven
 * reads the PIN from that popup ([readSonyPin]), only while it's waiting for one.
 */
class HomeOverrideService : AccessibilityService() {

    private var lastRedirect = 0L

    private val guard = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        if (rootInActiveWindow?.packageName == GOOGLE_TV_LAUNCHER) openHaven()
        // This service runs from internal storage for as long as the TV is on, so it also restores
        // other apps' accessibility services once their USB-stored apps load: every 20 s for the
        // first 10 minutes, then every 2 minutes.
        guard.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                runCatching {
                    AccessibilityGuard.check(applicationContext)
                    AccessibilityGuard.settle(applicationContext)
                }
                delay(if (System.currentTimeMillis() - start < 10 * 60_000L) 20_000L else 120_000L)
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        // Act on release; swallow the press too so Google TV's home never flashes up.
        if (event.action == KeyEvent.ACTION_UP) return openHaven()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != GOOGLE_TV_LAUNCHER) return
        // Only the home page itself; the launcher's other screens (profiles, accounts, inputs) stay usable.
        if (event.className?.toString()?.endsWith(".home.HomeActivity") != true) return
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

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 30 || out.size > 200) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, out, depth + 1) }
    }

    override fun onDestroy() {
        guard.cancel()
        connected = false
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    companion object {
        const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"

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

        /** Reads the PIN from Sony's registration popup, if it's on screen. */
        fun readSonyPin(): String? = instance?.findSonyPin()
    }
}
