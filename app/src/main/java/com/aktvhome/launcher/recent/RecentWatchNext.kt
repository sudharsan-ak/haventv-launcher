package com.aktvhome.launcher.recent

import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.graphics.Bitmap
import java.text.DateFormat
import java.util.Date

/**
 * Diagnostic fallback from Android TV Watch Next, not evidence of current playback.
 * Never selects another app's program and never starts a playback intent.
 */
internal object RecentWatchNext {
    private const val MAX_AGE_MS = 48 * 60 * 60_000L
    private const val MAX_ROWS = 500

    data class Result(val title: String?, val artwork: Bitmap?, val detail: String)

    fun find(context: Context, pkg: String): Result {
        if (context.checkSelfPermission("android.permission.READ_TV_LISTINGS") !=
            PackageManager.PERMISSION_GRANTED) {
            return Result(null, null, "Watch Next: READ_TV_LISTINGS permission missing")
        }
        data class Row(val title: String, val art: String?, val at: Long)
        val now = System.currentTimeMillis()
        val matches = mutableListOf<Row>()
        val read = runCatching {
            context.contentResolver.query(
                TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null
            )?.use { c ->
                val pkgIndex = c.getColumnIndex(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME)
                val titleIndex = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_TITLE)
                val atIndex = c.getColumnIndex(
                    TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
                val artIndex = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI)
                val thumbIndex = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI)
                val visibleIndex = c.getColumnIndex("browsable")
                if (pkgIndex < 0 || titleIndex < 0 || atIndex < 0) return@use 0
                var scanned = 0
                while (scanned < MAX_ROWS && c.moveToNext()) {
                    scanned++
                    if (visibleIndex >= 0 && !c.isNull(visibleIndex) &&
                        c.getInt(visibleIndex) == 0) continue
                    if (c.isNull(pkgIndex) || c.getString(pkgIndex) != pkg ||
                        c.isNull(titleIndex) || c.isNull(atIndex)) continue
                    val title = c.getString(titleIndex)?.trim().orEmpty()
                    val at = c.getLong(atIndex)
                    if (title.isBlank() || at <= 0L || at > now ||
                        now - at > MAX_AGE_MS) continue
                    val art = listOf(artIndex, thumbIndex).firstNotNullOfOrNull { index ->
                        if (index >= 0 && !c.isNull(index))
                            c.getString(index)?.takeIf { it.isNotBlank() } else null
                    }
                    matches += Row(title.take(100), art, at)
                }
                scanned
            } ?: 0
        }
        val scanned = read.getOrElse {
            return Result(null, null, "Watch Next query failed: " + it.javaClass.simpleName)
        }
        val selected = matches.maxByOrNull { it.at }
            ?: return Result(null, null,
                "Watch Next: $scanned scanned, no recent visible rows for this app")
        val art = RecentArtworkLoader.load(context, selected.art)
        val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(selected.at))
        return Result(selected.title, art,
            "Watch Next: ${matches.size} recent item(s) for app; latest engagement $at. " +
                "This item is NOT verified as the currently playing program." +
                if (selected.art != null && art == null) " Artwork URI could not be read." else "")
    }
}
