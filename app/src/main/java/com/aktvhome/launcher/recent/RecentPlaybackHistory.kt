package com.aktvhome.launcher.recent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.PlaybackState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/**
 * Opt-in history of metadata actually published by foreground MediaSessions.
 * Up to 5 titles per app, 30 artwork files globally; no protected video bytes.
 * This is metadata history, not an assertion that the item is playing now.
 */
internal object RecentPlaybackHistory {
    private const val PREF = "haven_recent_playback_history"
    private const val DIR = "haven_recent_playback_art"
    private const val MAX_TITLES_PER_APP = 5
    private const val MAX_IMAGES = 30
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60_000L

    data class Item(
        val title: String, val subtitle: String, val positionMs: Long,
        val durationMs: Long, val observedAt: Long, val artwork: Bitmap?
    )
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun folder(ctx: Context) = File(ctx.cacheDir, DIR)
    private fun key(pkg: String) = "items_" + pkg
    private fun imageName(pkg: String, title: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((pkg + "|" + title).toByteArray(Charsets.UTF_8))
        return bytes.take(16).joinToString("") { "%02x".format(it.toInt() and 255) } + ".jpg"
    }
    private fun rows(ctx: Context, pkg: String): JSONArray = try {
        JSONArray(prefs(ctx).getString(key(pkg), "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    /** Call on a single worker thread. Preserve a known resume position on stop/reset. */
    fun record(ctx: Context, pkg: String, title: String, subtitle: String,
               positionMs: Long, durationMs: Long, state: Int, artwork: Bitmap?) {
        val name = title.trim().take(100)
        if (name.isBlank() || !RecentPreviewStore.enabled(ctx)) return
        val now = System.currentTimeMillis()
        val old = rows(ctx, pkg)
        val kept = mutableListOf<JSONObject>()
        var previous: JSONObject? = null
        for (i in 0 until old.length()) {
            val row = old.optJSONObject(i) ?: continue
            if (row.optString("title") == name) previous = row
            else if (now - row.optLong("at", 0L) in 0..MAX_AGE_MS) kept += row
        }
        val oldPos = previous?.optLong("pos", -1L) ?: -1L
        val oldDuration = previous?.optLong("duration", 0L) ?: 0L
        val duration = if (durationMs > 0) durationMs else oldDuration
        val restarting = state == PlaybackState.STATE_STOPPED ||
            state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR
        val position = RecentPlaybackRules.retainPosition(positionMs, oldPos, restarting)
        val file = File(folder(ctx), imageName(pkg, name))
        if (artwork != null && artwork.width > 0 && artwork.height > 0) {
            val dir = folder(ctx)
            if (dir.isDirectory || dir.mkdirs()) {
                val temp = File(dir, file.name + ".tmp")
                try {
                    if (temp.outputStream().use { artwork.compress(Bitmap.CompressFormat.JPEG, 78, it) })
                        temp.renameTo(file)
                } catch (_: Exception) { /* keep existing artwork */ }
                temp.delete()
            }
        }
        val row = JSONObject()
            .put("title", name)
            .put("sub", subtitle.trim().take(100))
            .put("pos", position)
            .put("duration", duration)
            .put("at", now)
        // Discard finished items from resume history, but retain for diagnostics until next title.
        val list = mutableListOf<JSONObject>()
        if (!(duration > 0 && position > duration * 97 / 100)) list += row
        list += kept.sortedByDescending { it.optLong("at", 0L) }
            .take(MAX_TITLES_PER_APP - list.size)
        val validNames = list.map { imageName(pkg, it.optString("title")) }.toSet()
        old.let {
            for (i in 0 until it.length()) {
                val obsolete = it.optJSONObject(i)?.optString("title") ?: continue
                val filename = imageName(pkg, obsolete)
                if (filename !in validNames) File(folder(ctx), filename).delete()
            }
        }
        val json = JSONArray()
        list.forEach { json.put(it) }
        prefs(ctx).edit().putString(key(pkg), json.toString()).apply()
        pruneImages(ctx)
    }

    private fun pruneImages(ctx: Context) {
        folder(ctx).listFiles()?.filter { it.isFile && it.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_IMAGES)?.forEach { it.delete() }
    }

    fun latest(ctx: Context, pkg: String): Item? {
        val now = System.currentTimeMillis()
        val history = rows(ctx, pkg)
        val row = (0 until history.length())
            .mapNotNull { history.optJSONObject(it) }
            .filter { now - it.optLong("at", 0L) in 0..MAX_AGE_MS }
            .maxByOrNull { it.optLong("at", 0L) } ?: return null
        val title = row.optString("title")
        val artFile = File(folder(ctx), imageName(pkg, title))
        val image = if (artFile.isFile) runCatching {
            BitmapFactory.decodeFile(artFile.absolutePath)
        }.getOrNull() else null
        return Item(title, row.optString("sub"), row.optLong("pos", -1L),
            row.optLong("duration", 0L), row.optLong("at", 0L), image)
    }

    fun status(ctx: Context, pkg: String): String {
        // Diagnostics can render on the UI thread; inspect only JSON and file metadata.
        val all = rows(ctx, pkg)
        val now = System.currentTimeMillis()
        val last = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .filter { now - it.optLong("at", 0L) in 0..MAX_AGE_MS }
            .maxByOrNull { it.optLong("at", 0L) }
            ?: return "Playback history: no recorded titles for this app"
        val title = last.optString("title")
        val at = last.optLong("at", 0L)
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(at))
        val pos = last.optLong("pos", -1L)
        val duration = last.optLong("duration", 0L)
        val progress = if (duration > 0L && pos >= 0L)
            " | resume " + pos / 1000 + "s / " + duration / 1000 + "s"
        else " | position unavailable"
        return "Playback history: " + all.length() + " title(s), latest " +
            title.take(50) + progress + " | observed " + stamp +
            (if (File(folder(ctx), imageName(pkg, title)).isFile)
                " | title artwork" else " | no title artwork")
    }

    fun clearAll(ctx: Context) {
        folder(ctx).listFiles()?.forEach { it.delete() }
        prefs(ctx).edit().clear().apply()
    }
}
