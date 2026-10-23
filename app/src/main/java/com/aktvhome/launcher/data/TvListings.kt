package com.aktvhome.launcher.data

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri

/** Something an app published to the TV that Haven can open directly. */
data class TvItem(
    val pkg: String,
    val title: String,
    /** "S3 E3 · Phil On Wire", a description, or "". */
    val detail: String,
    /** 0..1 when the app reported a sensible position, else null. */
    val progress: Float?,
    val live: Boolean,
    val intentUri: String?,
    /** Artwork or channel logo URL, when the app shares one. */
    val art: String? = null,
)

/** One app's live channel row, e.g. YouTube TV with ESPN, NBC 5, USA. */
data class LiveGroup(val pkg: String, val provider: String, val items: List<TvItem>)

/**
 * Reads what apps publish to Android TV: the shared Watch Next list (continue watching) and
 * app channel rows. Works for any app that publishes; nothing here is specific to one service.
 * Needs READ_TV_LISTINGS (granted once over ADB); without it the lists are just empty.
 */
class TvListings(private val context: Context) {

    fun continueWatching(limit: Int = 12): List<TvItem> =
        query(TvContract.WatchNextPrograms.CONTENT_URI) { c ->
            if (c.int("browsable") == 0) return@query null
            val duration = c.long("duration_millis")
            val position = c.long("last_playback_position_millis")
            // Some apps report nonsense (a 1-second duration); only show progress that adds up.
            val progress = if (duration > 60_000 && position in 1 until duration) position.toFloat() / duration else null
            val season = c.str("season_display_number")
            val episode = c.str("episode_display_number")
            val epTitle = c.str("episode_title")
            val number = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" ")
            c.long("last_engagement_time_utc_millis") to TvItem(
                pkg = c.str("package_name") ?: return@query null,
                title = c.str("title") ?: return@query null,
                detail = listOf(number, epTitle.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                progress = progress,
                live = false,
                intentUri = c.str("intent_uri"),
                art = c.str("poster_art_uri") ?: c.str("thumbnail_uri"),
            )
        }.sortedByDescending { it.first }.map { it.second }.distinctBy { it.pkg to it.title }.take(limit)

    /** Every app's shared rows (YouTube TV's channels, ESPN's live shows, …), grouped by app. */
    fun appRows(labelOf: (String) -> String): List<LiveGroup> {
        val channelPkg = mutableMapOf<Long, String>()
        query(TvContract.Channels.CONTENT_URI) { c ->
            if (c.str("type") == TvContract.Channels.TYPE_PREVIEW) channelPkg[c.long("_id")] = c.str("package_name") ?: ""
            null
        }
        val items = query(TvContract.PreviewPrograms.CONTENT_URI) { c ->
            if (c.int("browsable") == 0) return@query null
            val pkg = c.str("package_name") ?: channelPkg[c.long("channel_id")] ?: return@query null
            TvItem(
                pkg, c.str("title") ?: return@query null, c.str("short_description").orEmpty(), null,
                c.int("live") == 1, c.str("intent_uri"),
                art = c.str("logo_uri") ?: c.str("poster_art_uri") ?: c.str("thumbnail_uri"),
            )
        }
        return items.groupBy { it.pkg }.map { (pkg, list) -> LiveGroup(pkg, labelOf(pkg), list.distinctBy { it.title }) }
    }

    /** Opens an item in its app: the app's own link first, then just the app. */
    fun open(item: TvItem): Boolean {
        val uri = item.intentUri
        val intent = when {
            uri == null -> null
            uri.startsWith("intent:") -> runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(item.pkg)
        }
        val launch = intent ?: context.packageManager.getLeanbackLaunchIntentForPackage(item.pkg)
            ?: context.packageManager.getLaunchIntentForPackage(item.pkg) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private fun <T> query(uri: Uri, map: (Cursor) -> T?): List<T> = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) runCatching { map(c) }.getOrNull()?.let { add(it) } }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun Cursor.idx(name: String) = getColumnIndex(name)
    private fun Cursor.str(name: String): String? = idx(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }?.takeIf { it.isNotBlank() }
    private fun Cursor.int(name: String): Int = idx(name).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: 0
    private fun Cursor.long(name: String): Long = idx(name).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) } ?: 0L
}
