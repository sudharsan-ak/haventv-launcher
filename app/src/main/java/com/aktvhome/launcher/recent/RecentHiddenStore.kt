package com.aktvhome.launcher.recent

import android.content.Context

/**
 * Haven-only visibility for Recents. Never removes an Android task or stops a process.
 * Task IDs are used (not package-only) so new task instances can appear normally.
 */
internal object RecentHiddenStore {
    private const val PREF = "haven_recents_hidden"
    private const val PREFIX = "hidden_"
    private const val OPEN_GRACE_MS = 2_500L
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isHidden(ctx: Context, taskId: Int): Boolean =
        prefs(ctx).contains(PREFIX + taskId)

    fun hide(ctx: Context, task: RecentTask) {
        prefs(ctx).edit().putString(PREFIX + task.taskId,
            task.pkg + "|" + System.currentTimeMillis()).apply()
    }

    fun hideAll(ctx: Context, tasks: List<RecentTask>) {
        val now = System.currentTimeMillis()
        val edit = prefs(ctx).edit()
        tasks.forEach { edit.putString(PREFIX + it.taskId, it.pkg + "|" + now) }
        edit.apply()
    }

    /**
     * When a user opens an app again from Haven/another launcher, show it in Recents.
     * A short grace period prevents the app underneath a just-closed switcher from
     * immediately undoing a hide operation.
     */
    fun onForeground(ctx: Context, packageName: String) {
        if (packageName == ctx.packageName || packageName.isBlank()) return
        val now = System.currentTimeMillis()
        val keys = prefs(ctx).all.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX)) return@mapNotNull null
            val entry = value as? String ?: return@mapNotNull null
            val separator = entry.lastIndexOf('|')
            if (separator <= 0 || entry.substring(0, separator) != packageName)
                return@mapNotNull null
            val at = entry.substring(separator + 1).toLongOrNull() ?: 0L
            if (now - at >= OPEN_GRACE_MS) key else null
        }
        if (keys.isEmpty()) return
        val edit = prefs(ctx).edit()
        keys.forEach { edit.remove(it) }
        edit.apply()
    }

    fun showAll(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
