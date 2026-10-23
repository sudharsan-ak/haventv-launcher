package com.aktvhome.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Keeps your accessibility services switched on across restarts (the Accessibility Watchdog's job).
 *
 * Apps on a USB drive used as storage load after Android checks accessibility services at boot,
 * so Android removes their services from the enabled list. Haven runs from internal storage, so it
 * remembers the list you had and re-adds any service whose app is installed again.
 *
 * Removal you do yourself is respected, but only when it's clearly yours: an earlier check this
 * boot saw the service on, its app is loaded now, and the first 10 minutes after boot are over.
 * Anything missing at the first check after a boot, or during those 10 minutes, was dropped by the
 * boot and comes back. (The decisions are in [GuardRules].)
 */
object AccessibilityGuard {

    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    /** Run periodically (and at boot). Returns how many services it re-enabled. */
    fun check(context: Context): Int {
        if (!PrivateDns.canWrite(context)) return 0
        val prefs = context.getSharedPreferences("a11y_guard", Context.MODE_PRIVATE)
        val current = read(context)
        val boot = bootCount(context)
        val state = prefs.getString("wanted", null)?.let { w ->
            // What the last check saw only counts within the same boot.
            val seen = if (prefs.getInt("seenBoot", -1) == boot) split(prefs.getString("seen", null)) else emptySet()
            GuardState(split(w), seen)
        }
        // Just booted: by the clock since boot, not only the boot flag, which can come late.
        val booting = SystemClock.elapsedRealtime() < GuardRules.BOOT_WINDOW_MS || prefs.getBoolean("bootPending", false)
        val r = GuardRules.decide(state, current, { available(context, it) }, booting)

        if (r.restore.isNotEmpty()) {
            Settings.Secure.putString(context.contentResolver, KEY, (current + r.restore).joinToString(":"))
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        }
        prefs.edit()
            .putString("wanted", r.state.wanted.joinToString(":"))
            .putString("seen", r.state.seen.joinToString(":"))
            .putInt("seenBoot", boot)
            .apply()
        return r.restore.size
    }

    private fun split(saved: String?): Set<String> = saved.orEmpty().split(':').filter { it.isNotBlank() }.toSet()

    /** Android's boot counter; changes on every boot. */
    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** Called at boot: until the drive's apps are back, missing services were dropped by the boot. */
    fun onBoot(context: Context) {
        context.getSharedPreferences("a11y_guard", Context.MODE_PRIVATE).edit()
            .putBoolean("bootPending", true).putLong("bootAt", System.currentTimeMillis()).apply()
    }

    /** Ends the post-boot window once every wanted app is loaded (or after 10 minutes). */
    fun settle(context: Context) {
        val prefs = context.getSharedPreferences("a11y_guard", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("bootPending", false)) return
        val wanted = prefs.getString("wanted", "")!!.split(':').filter { it.isNotBlank() }
        val allLoaded = wanted.all { available(context, it) }
        val expired = System.currentTimeMillis() - prefs.getLong("bootAt", 0L) > 10 * 60_000L
        if (allLoaded && wanted.all { it in read(context) } || expired) prefs.edit().putBoolean("bootPending", false).apply()
    }

    private fun read(context: Context): Set<String> =
        (Settings.Secure.getString(context.contentResolver, KEY) ?: "").split(':').filter { it.isNotBlank() }.map(GuardRules::normal).toSet()

    /** True if the service's app is installed and loaded (its storage is mounted). */
    private fun available(context: Context, entry: String): Boolean {
        val cn = ComponentName.unflattenFromString(entry) ?: return false
        return runCatching { context.packageManager.getServiceInfo(cn, PackageManager.GET_META_DATA); true }.getOrDefault(false)
    }
}
