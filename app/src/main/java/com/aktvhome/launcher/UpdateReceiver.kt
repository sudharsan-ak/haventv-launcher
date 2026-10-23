package com.aktvhome.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aktvhome.launcher.data.HomeOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs right after Haven is updated. An update can leave the Home button service enabled but
 * deaf, and at that moment Haven isn't open to notice, so reconnect it here.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                HomeOverride.reconnectIfStale(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
