package com.aktvhome.launcher.data

import android.content.Context
import android.provider.Settings

/**
 * Switches Haven's Home button service on and off. Edits only Haven's own entry in the TV's
 * accessibility list; every other app's service is left exactly as it was. Needs the same
 * WRITE_SECURE_SETTINGS grant as Private DNS.
 */
object HomeOverride {

    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    private fun component(context: Context) = "${context.packageName}/${context.packageName}.HomeOverrideService"

    private fun entries(context: Context): List<String> =
        (Settings.Secure.getString(context.contentResolver, KEY) ?: "").split(':').filter { it.isNotBlank() }

    fun isOn(context: Context): Boolean {
        val me = component(context)
        val short = "${context.packageName}/.HomeOverrideService"
        return entries(context).any { it == me || it == short }
    }

    /**
     * After Haven is updated, Android may keep the Home service listed as on but never reconnect
     * it, so Home stops opening Haven. If it's on but hasn't connected, switch it off and on.
     */
    suspend fun reconnectIfStale(context: Context) {
        if (!isOn(context)) return
        // Updates are the known trigger, and the service can look connected while getting no events,
        // so reconnect once per install rather than trusting the connected flag alone.
        val prefs = context.getSharedPreferences("home_override", Context.MODE_PRIVATE)
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val stale = prefs.getLong("reconnectedFor", -1L) != installed || !com.aktvhome.launcher.HomeOverrideService.connected
        if (!stale) return
        if (!set(context, false)) return
        // Android ignores an off/on that happens too quickly; give it time to unbind first.
        kotlinx.coroutines.delay(2_000)
        set(context, true)
        prefs.edit().putLong("reconnectedFor", installed).apply()
    }

    /** Returns false when Haven doesn't have the one-time ADB permission. */
    fun set(context: Context, on: Boolean): Boolean {
        if (!PrivateDns.canWrite(context)) return false
        val me = component(context)
        val short = "${context.packageName}/.HomeOverrideService"
        val others = entries(context).filter { it != me && it != short }
        val next = if (on) others + me else others
        return runCatching {
            Settings.Secure.putString(context.contentResolver, KEY, next.joinToString(":"))
            if (on) Settings.Secure.putString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            true
        }.getOrDefault(false)
    }
}
