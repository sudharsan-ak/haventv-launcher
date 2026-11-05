package com.aktvhome.launcher.recent

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.net.Uri
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Android TV's Watch Next and published preview channels.
 * These provide suggestions/resume hints, not app screenshots or confirmed live playback.
 * Queries run off the UI thread and are filtered by source package.
 */
internal object RecentWatchNext {
    private const val MAX_ROWS_PER_TABLE = 350
    private const val MAX_PROGRAM_AGE = 7L * 24 * 60 * 60_000L

    data class Result(
        val title: String?, val artwork: Bitmap?, val detail: String,
        val label: String = "TV program artwork (not current playback)"
    )
    internal data class Candidate(
        val title: String, val artworkUri: String?, val source: String,
        val rank: Int, val observedAt: Long, val progressMs: Long,
        val durationMs: Long
    )
    private data class Channel(val name: String, val resume: Boolean)
    private data class Tally(val scanned: Int, val error: String?)
    private fun packages(pkg: String): Set<String> = when (pkg) {
        "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient" ->
            setOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
        "com.wbd.stream", "com.hbo.hbonow" ->
            setOf("com.wbd.stream", "com.hbo.hbonow")
        else -> setOf(pkg)
    }
    private fun Cursor.text(col: String): String? {
        val i = getColumnIndex(col)
        return if (i >= 0 && !isNull(i)) getString(i)?.trim() else null
    }
    private fun Cursor.number(col: String, fallback: Long = 0L): Long {
        val i = getColumnIndex(col)
        return if (i >= 0 && !isNull(i)) runCatching { getLong(i) }.getOrDefault(fallback)
        else fallback
    }
    private fun Cursor.visible(): Boolean = number("browsable", 1L) != 0L

    /** Do not use SQL selection; TV provider access can be restricted to browsable rows. */
    private fun query(context: Context, uri: Uri, each: (Cursor) -> Unit): Tally {
        var count = 0
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: return Tally(0, "Provider returned null cursor")
            cursor.use { c ->
                while (count < MAX_ROWS_PER_TABLE && c.moveToNext()) {
                    count++
                    each(c)
                }
            }
            Tally(count, null)
        } catch (ex: Exception) {
            Tally(count, ex.javaClass.simpleName)
        }
    }

    internal fun priority(isWatchNext: Boolean, position: Long, namedResume: Boolean): Int =
        when {
            isWatchNext && position > 0L -> 0
            !isWatchNext && position > 0L -> 1
            !isWatchNext && namedResume -> 2
            isWatchNext -> 3
            else -> 4
        }

    fun find(context: Context, pkg: String): Result {
        if (context.checkSelfPermission("android.permission.READ_TV_LISTINGS") !=
            PackageManager.PERMISSION_GRANTED) {
            return Result(null, null, "TV provider: READ_TV_LISTINGS not granted")
        }
        val candidates = packages(pkg)
        val now = System.currentTimeMillis()
        val channelMap = mutableMapOf<Long, Channel>()
        val rows = mutableListOf<Candidate>()
        val channelScan = query(context, TvContract.Channels.CONTENT_URI) { c ->
            val owner = c.text("package_name") ?: return@query
            if (!c.visible() || owner !in candidates) return@query
            val id = c.number("_id", -1L)
            if (id < 0) return@query
            val name = c.text("display_name").orEmpty()
            val low = name.lowercase(Locale.ROOT)
            val resume = listOf("continue", "watch next", "up next", "resume",
                "recent", "history", "keep watching").any { it in low }
            channelMap[id] = Channel(name, resume)
        }
        val watchScan = query(context, TvContract.WatchNextPrograms.CONTENT_URI) { c ->
            val owner = c.text("package_name") ?: return@query
            if (!c.visible() || owner !in candidates) return@query
            val title = c.text("title") ?: c.text("episode_title") ?: return@query
            if (title.isBlank()) return@query
            val engaged = c.number("last_engagement_time_utc_millis")
            if (engaged > now || (engaged > 0 && now - engaged > MAX_PROGRAM_AGE)) return@query
            val pos = c.number("last_playback_position_millis", -1L)
            val dur = c.number("duration_millis")
            if (dur > 0 && pos >= dur * 97 / 100) return@query
            val artwork = c.text("thumbnail_uri") ?: c.text("poster_art_uri")
                ?: c.text("logo_uri")
            rows += Candidate(title.take(100), artwork, "Watch Next",
                priority(true, pos, false), engaged, pos, dur)
        }
        val previewScan = query(context, TvContract.PreviewPrograms.CONTENT_URI) { c ->
            val owner = c.text("package_name") ?: return@query
            if (!c.visible() || owner !in candidates) return@query
            // Some OEM TV providers hide their Channels rows, yet expose PreviewPrograms.
            // A matching package_name still makes the preview row safe to consider.
            val channel = channelMap[c.number("channel_id", -1L)]
            val title = c.text("title") ?: c.text("episode_title") ?: return@query
            if (title.isBlank()) return@query
            val pos = c.number("last_playback_position_millis", -1L)
            val dur = c.number("duration_millis")
            if (dur > 0 && pos >= dur * 97 / 100) return@query
            val engaged = c.number("last_engagement_time_utc_millis")
            if (engaged > now || (engaged > 0 && now - engaged > MAX_PROGRAM_AGE)) return@query
            val artwork = c.text("thumbnail_uri") ?: c.text("poster_art_uri")
                ?: c.text("logo_uri")
            val source = "Preview channel" +
                if (channel?.name.isNullOrBlank()) "" else " (" +
                    channel!!.name.take(38) + ")"
            rows += Candidate(title.take(100), artwork, source,
                priority(false, pos, channel?.resume == true), engaged, pos, dur)
        }

        val stats = "TV provider: channels " + channelMap.size + "/" + channelScan.scanned +
            ", Watch Next " + watchScan.scanned + " scanned, preview " +
            previewScan.scanned + " scanned; matched " + rows.size
        val errors = listOfNotNull(
            channelScan.error?.let { "channels: $it" },
            watchScan.error?.let { "Watch Next: $it" },
            previewScan.error?.let { "preview: $it" }
        ).joinToString("; ")
        val unique = rows.sortedWith(compareBy<Candidate> { it.rank }
            .thenByDescending { it.observedAt }
            .thenByDescending { it.progressMs })
            .distinctBy { it.title.lowercase(Locale.ROOT) }
        if (unique.isEmpty()) return Result(null, null,
            stats + (if (errors.isBlank()) " | No accessible programs for app"
            else " | Errors: $errors"))
        var selected = unique.first()
        var artwork: Bitmap? = null
        for (candidate in unique.take(4)) {
            val picture = RecentArtworkLoader.load(context, candidate.artworkUri)
            if (picture != null) {
                selected = candidate
                artwork = picture
                break
            }
        }
        val progress = if (selected.durationMs > 0 && selected.progressMs >= 0)
            " | " + selected.progressMs / 1000 + "s/" + selected.durationMs / 1000 + "s"
        else ""
        val time = if (selected.observedAt > 0L)
            " | engaged " + DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(Date(selected.observedAt))
        else ""
        val label = selected.source + " artwork (not verified playing)"
        return Result(selected.title, artwork,
            stats + " | Chosen: " + selected.source + ", " + selected.title + progress + time +
                ". Program data does not establish current playback." +
                (if (errors.isBlank()) "" else " | Errors: " + errors),
            label)
    }
}
