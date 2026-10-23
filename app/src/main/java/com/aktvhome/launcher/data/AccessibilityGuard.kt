package com.aktvhome.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Keeps your accessibility services switched on across restarts (the Accessibility Watchdog's job).
 *
 * Apps on a USB drive used as storage load after Android checks accessibility services at boot,
 * so Android removes their services from the enabled list. Haven runs from internal storage, so it
 * remembers the list you had and re-adds any service whose app is installed again.
 *
 * Removal you do yourself is respected: a service that disappears while its app is installed and
 * loaded counts as switched off on purpose, and is not brought back.
 */
object AccessibilityGuard {

    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    /** Run periodically (and at boot). Returns how many services it re-enabled. */
    fun check(context: Context): Int {
        if (!PrivateDns.canWrite(context)) return 0
        val prefs = context.getSharedPreferences("a11y_guard", Context.MODE_PRIVATE)
        val current = read(context)
        val wanted = prefs.getString("wanted", null)?.split(':')?.filter { it.isNotBlank() }?.toSet()

        if (wanted == null) {
            // First run: whatever is on now is what you want.
            prefs.edit().putString("wanted", current.joinToString(":")).apply()
            return 0
        }

        val missing = wanted - current
        // Missing while its app is loaded = you switched it off; forget it. Missing while its app
        // isn't loaded yet (USB drive not mounted) = keep wanting it.
        val switchedOff = missing.filter { available(context, it) && !justLoaded(prefs, it) }.toSet()
        val toRestore = missing.filter { available(context, it) && justLoaded(prefs, it) }
        // New services you turned on join the wanted list.
        var nextWanted = (wanted - switchedOff) + current

        if (toRestore.isNotEmpty()) {
            val restored = current + toRestore
            Settings.Secure.putString(context.contentResolver, KEY, restored.joinToString(":"))
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            nextWanted = nextWanted + toRestore
        }
        // Remember which wanted services' apps were unavailable this round.
        val unavailable = nextWanted.filter { !available(context, it) }
        prefs.edit()
            .putString("wanted", nextWanted.joinToString(":"))
            .putString("unavailable", unavailable.joinToString(":"))
            .apply()
        return toRestore.size
    }

    /**
     * True if [entry]'s app was unavailable on an earlier check (or since boot), i.e. it was dropped
     * because its storage wasn't ready rather than switched off by you.
     */
    private fun justLoaded(prefs: android.content.SharedPreferences, entry: String): Boolean =
        entry in (prefs.getString("unavailable", "") ?: "").split(':') || prefs.getBoolean("bootPending", false)

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
        (Settings.Secure.getString(context.contentResolver, KEY) ?: "").split(':').filter { it.isNotBlank() }.toSet()

    /** True if the service's app is installed and loaded (its storage is mounted). */
    private fun available(context: Context, entry: String): Boolean {
        val cn = ComponentName.unflattenFromString(entry) ?: return false
        return runCatching { context.packageManager.getServiceInfo(cn, PackageManager.GET_META_DATA); true }.getOrDefault(false)
    }
}
