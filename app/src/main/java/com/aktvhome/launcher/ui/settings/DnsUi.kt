package com.aktvhome.launcher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.aktvhome.launcher.data.DnsChoice
import com.aktvhome.launcher.data.DnsStatus
import com.aktvhome.launcher.ui.HavenState
import kotlinx.coroutines.delay

/** What the DNS controls show, refreshed every couple of seconds while they're on screen. */
data class DnsView(val status: DnsStatus, val active: DnsChoice?, val label: String)

/**
 * Polls because Android verifies a new DNS server in the background; the status changes a
 * few seconds after a switch without any callback we can easily hook from here.
 */
@Composable
fun rememberDnsView(state: HavenState): State<DnsView> {
    val view = remember { mutableStateOf(read(state)) }
    LaunchedEffect(Unit) {
        while (true) {
            view.value = read(state)
            delay(2_000)
        }
    }
    return view
}

private fun read(state: HavenState): DnsView {
    val (mode, spec) = state.dnsMode()
    val active = when (mode) {
        "off" -> DnsChoice.OFF
        "opportunistic" -> DnsChoice.AUTO
        "hostname" -> DnsChoice.entries.firstOrNull { it.host != null && it.host == spec } ?: DnsChoice.CUSTOM
        else -> null
    }
    val label = when (active) {
        DnsChoice.CUSTOM -> spec ?: "Custom"
        null -> mode
        else -> active.label
    }
    return DnsView(state.dnsStatus(), active, label)
}
