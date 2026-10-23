package com.aktvhome.launcher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/** The DNS choices offered. [host] is only used in hostname (strict) mode. */
enum class DnsChoice(val label: String, val host: String?, val note: String) {
    OFF("Off", null, "Plain DNS from your router"),
    AUTO("Auto", null, "Encrypted only if the network offers it"),
    ADGUARD("AdGuard", "dns.adguard-dns.com", "Blocks ads and trackers"),
    CLOUDFLARE("Cloudflare", "one.one.one.one", "Fast, no filtering"),
    QUAD9("Quad9", "dns.quad9.net", "Blocks known malicious sites"),
    CUSTOM("Custom", null, "Any hostname you type"),
}

/** What the TV is doing right now, for the status line. */
data class DnsStatus(val text: String, val ok: Boolean)

/**
 * Reads and sets the TV's Private DNS. Writing needs WRITE_SECURE_SETTINGS, granted once over
 * ADB (see [GRANT_COMMAND]); without it Haven can still show the current state.
 */
object PrivateDns {

    const val GRANT_COMMAND = "adb shell pm grant com.aktvhome.launcher android.permission.WRITE_SECURE_SETTINGS"

    private const val MODE = "private_dns_mode"
    private const val SPEC = "private_dns_specifier"

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** The TV's current mode and hostname. An unset mode is Android's default, Auto. */
    fun current(context: Context): Pair<String, String?> {
        val cr = context.contentResolver
        return (Settings.Global.getString(cr, MODE) ?: "opportunistic") to Settings.Global.getString(cr, SPEC)
    }

    /** True if the TV already matches [choice] (with [custom] as the Custom hostname). */
    fun matches(context: Context, choice: DnsChoice, custom: String): Boolean {
        val (mode, spec) = current(context)
        return when (choice) {
            DnsChoice.OFF -> mode == "off"
            DnsChoice.AUTO -> mode == "opportunistic"
            DnsChoice.CUSTOM -> mode == "hostname" && spec == custom
            else -> mode == "hostname" && spec == choice.host
        }
    }

    /** Applies [choice]. Returns false when the permission hasn't been granted. */
    fun apply(context: Context, choice: DnsChoice, custom: String): Boolean {
        if (!canWrite(context)) return false
        val cr = context.contentResolver
        return runCatching {
            when (choice) {
                DnsChoice.OFF -> Settings.Global.putString(cr, MODE, "off")
                DnsChoice.AUTO -> Settings.Global.putString(cr, MODE, "opportunistic")
                else -> {
                    val host = if (choice == DnsChoice.CUSTOM) custom.trim() else choice.host!!
                    if (host.isEmpty()) return false
                    Settings.Global.putString(cr, SPEC, host)
                    Settings.Global.putString(cr, MODE, "hostname")
                }
            }
            true
        }.getOrDefault(false)
    }

    /** Never throws: a status line that can't be read must not take Haven down with it. */
    fun status(context: Context): DnsStatus =
        runCatching { readStatus(context) }.getOrElse { DnsStatus("Status unavailable", false) }

    private fun readStatus(context: Context): DnsStatus {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return DnsStatus("Unknown", false)
        val net = cm.activeNetwork ?: return DnsStatus("No network", false)
        val caps = cm.getNetworkCapabilities(net)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return DnsStatus("Overridden by a VPN (NordVPN or the AdGuard app)", false)
        }
        val lp = cm.getLinkProperties(net) ?: return DnsStatus("Unknown", false)
        val (mode, _) = current(context)
        return when {
            mode == "hostname" && lp.isPrivateDnsActive -> DnsStatus("Active and verified: ${lp.privateDnsServerName}", true)
            mode == "hostname" -> DnsStatus("Can't reach the server. Apps may lose internet until it answers.", false)
            mode == "off" -> DnsStatus("Off: plain DNS from your router", false)
            lp.isPrivateDnsActive -> DnsStatus("Auto: encrypted with your network's DNS", true)
            else -> DnsStatus("Auto: not encrypted (network doesn't offer it)", false)
        }
    }

    /** Puts the saved choice back if something changed it. Used at boot and when Haven opens. */
    fun enforce(context: Context, prefs: DnsPrefs) {
        if (!prefs.enforce) return
        if (!matches(context, prefs.choice, prefs.custom)) apply(context, prefs.choice, prefs.custom)
    }
}

/** The DNS part of Haven's settings, readable without Compose (for the boot receiver). */
data class DnsPrefs(val choice: DnsChoice, val custom: String, val enforce: Boolean)
