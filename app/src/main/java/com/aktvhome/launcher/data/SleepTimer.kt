package com.aktvhome.launcher.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Haven's own sleep timer: after the chosen minutes, the TV powers off through Sony's control
 * service (the same as the power button in Quick settings). Sony's timer isn't reachable from its
 * control service, so the two are separate. A restart clears it.
 */
object SleepTimer {

    val CHOICES = listOf(15, 30, 60, 90, 120)

    /** When the TV will power off (epoch ms), or null when no timer is set. */
    fun endsAt(context: Context): Long? =
        prefs(context).getLong("endsAt", 0L).takeIf { it > System.currentTimeMillis() }

    fun set(context: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        prefs(context).edit().putLong("endsAt", at).apply()
    }

    fun cancel(context: Context) {
        alarms(context).cancel(pending(context))
        prefs(context).edit().remove("endsAt").apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepTimerReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires when the sleep timer runs out: powers the TV off. */
class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        val app = context.applicationContext
        app.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).edit().remove("endsAt").apply()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SonyControl(app).powerOff()
            } finally {
                done.finish()
            }
        }
    }
}
