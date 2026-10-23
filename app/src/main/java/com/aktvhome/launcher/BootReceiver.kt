package com.aktvhome.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aktvhome.launcher.data.PrivateDns
import com.aktvhome.launcher.data.SettingsStore

/** At boot, restores the chosen Private DNS if "Keep enforced" is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        PrivateDns.enforce(context, SettingsStore.readDns(context))
    }
}
