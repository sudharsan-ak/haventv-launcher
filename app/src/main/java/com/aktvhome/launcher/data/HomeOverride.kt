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
