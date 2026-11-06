package com.aktvhome.launcher.recent

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import com.aktvhome.launcher.HavenMediaListener
import com.aktvhome.launcher.data.NbaAlerts

/**
 * Preview alternatives for apps whose screenshots cannot show protected video.
 * Every alternative is labeled by source, never presented as a real task snapshot.
 */
internal object RecentMediaArtwork {
    data class Preview(val bitmap: Bitmap?, val label: String, val detail: String)

    /** Called on Dispatchers.IO; actual screenshots are selected BEFORE this method. */
    fun find(context: Context, pkg: String): Preview {
        val saved = RecentMediaStore.get(context, pkg)
        val history = RecentPlaybackHistory.latest(context, pkg)
        val historyStatus = RecentPlaybackHistory.status(context, pkg)

        // A saved bitmap from the foreground session is better than a suggested program.
        if (saved?.image != null) return Preview(
            render(context, pkg, saved.title, saved.image),
            "Last foreground MediaSession artwork",
            RecentMediaStore.status(context, pkg) + " | " + historyStatus
        )
        if (history?.artwork != null) return Preview(
            render(context, pkg, history.title, history.artwork),
            "Recent played-title artwork (not live screenshot)",
            historyStatus + " | " + RecentMediaStore.status(context, pkg)
        )

        val active = liveSession(context, pkg)
        if (active.bitmap != null && active.detail.contains("artwork: available")) {
            return active.copy(detail = active.detail + " | " + historyStatus)
        }

        // Provider titles are recommendations/history, NOT the exact currently playing frame.
        val programs = RecentWatchNext.find(context, pkg)
        if (programs.artwork != null) return Preview(
            render(context, pkg, programs.title.orEmpty(), programs.artwork),
            programs.label,
            programs.detail + " | " + historyStatus
        )
        if (programs.title != null) return Preview(
            render(context, pkg, programs.title, null),
            programs.label.replace("artwork", "title"),
            programs.detail + " | " + historyStatus
        )

        // A generic app name like "PrimeVideo" is NOT a TV-show title.
        val title = listOfNotNull(saved?.title, history?.title)
            .firstOrNull { isTitleSpecific(pkg, it) }.orEmpty()
        if (title.isNotBlank()) return Preview(
            render(context, pkg, title, null),
            "Recent MediaSession title (no artwork)",
            RecentMediaStore.status(context, pkg) + " | " + historyStatus +
                " | " + programs.detail
        )
        return Preview(null, "App artwork fallback",
            RecentMediaStore.status(context, pkg) + " | " + historyStatus +
                " | " + active.detail + " | " + programs.detail)
    }

    private fun isTitleSpecific(pkg: String, title: String): Boolean {
        val cleaned = title.trim().lowercase()
        if (cleaned.isBlank()) return false
        val generic = setOf("primevideo", "prime video", "netflix", "youtube tv",
            "youtubetv", "smarttube", "amazon prime video")
        return cleaned !in generic && cleaned != pkg.lowercase()
    }

    private fun liveSession(context: Context, pkg: String): Preview {
        if (!NbaAlerts.canSeePlaying(context)) return Preview(null, "App artwork fallback",
            "Notification access is disabled: cannot inspect active media sessions")
        return try {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            val sessions = manager.getActiveSessions(
                ComponentName(context, HavenMediaListener::class.java))
            val controller = sessions.firstOrNull { it.packageName == pkg }
                ?: return Preview(null, "App artwork fallback", "No active MediaSession for app")
            val metadata = controller.metadata
                ?: return Preview(null, "App artwork fallback", "Active session has no metadata")
            val title = listOfNotNull(
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                metadata.description?.title?.toString(),
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            val raw = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: metadata.description?.iconBitmap
            var art = RecentArtworkLoader.scale(raw)
            if (art == null) art = listOfNotNull(
                metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                metadata.description?.iconUri?.toString(),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            ).firstNotNullOfOrNull { RecentArtworkLoader.load(context, it) }
            if (art == null && title.isBlank()) return Preview(null, "App artwork fallback",
                "Active session publishes no readable title or artwork")
            Preview(render(context, pkg, title, art),
                "Active media session (not foreground screenshot)",
                "MediaSession title: " + title.ifBlank { "(none)" } +
                    " | artwork: " + if (art == null) "none" else "available")
        } catch (e: Exception) {
            Preview(null, "App artwork fallback",
                "MediaSession read failed: " + e.javaClass.simpleName)
        }
    }

    private fun render(context: Context, pkg: String, title: String, art: Bitmap?): Bitmap? =
        runCatching {
            val canvasImage = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasImage)
            canvas.drawColor(Color.rgb(18, 27, 35))
            if (art != null && art.width > 0 && art.height > 0) {
                // Fill the 16:9 card without distorting wide thumbnails or portrait posters.
                val ratio = maxOf(480f / art.width, 270f / art.height)
                val w = art.width * ratio
                val h = art.height * ratio
                canvas.drawBitmap(art, null,
                    RectF((480f - w) / 2f, (270f - h) / 2f,
                        (480f + w) / 2f, (270f + h) / 2f), null)
            } else drawAppIcon(context, pkg, canvas)
            if (title.isNotBlank()) {
                val shade = Paint().apply {
                    shader = LinearGradient(0f, 150f, 0f, 270f,
                        0x00000000, 0xe9000000.toInt(), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 145f, 480f, 270f, shade)
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 21f
                    isFakeBoldText = true
                }
                val clean = title.trim().take(100)
                val display = if (clean.length > 39) clean.take(36) + "..." else clean
                canvas.drawText(display, 18f, 247f, text)
            }
            canvasImage
        }.getOrNull()

    /** The final fallback, from the installed application's own banner or icon. */
    fun appFallback(context: Context, pkg: String): Bitmap? = runCatching {
        val image = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(18, 28, 34))
        val banner = context.packageManager.getApplicationBanner(pkg)
        val drawable = banner ?: context.packageManager.getApplicationIcon(pkg)
        if (banner == null) drawable.setBounds(176, 71, 304, 199)
        else drawable.setBounds(0, 0, 480, 270)
        drawable.draw(canvas)
        image
    }.getOrNull()

    private fun drawAppIcon(context: Context, pkg: String, canvas: Canvas) {
        runCatching {
            val icon = context.packageManager.getApplicationIcon(pkg)
            icon.setBounds(176, 58, 304, 186)
            icon.draw(canvas)
        }
    }
}
