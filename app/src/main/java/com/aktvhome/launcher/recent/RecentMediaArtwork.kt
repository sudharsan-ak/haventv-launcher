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

    /** Called on Dispatchers.IO from the diagnostics; never from the TV UI thread. */
    fun find(context: Context, pkg: String): Preview {
        val saved = RecentMediaStore.get(context, pkg)
        if (saved != null) return Preview(
            render(context, pkg, saved.title, saved.image),
            "Last foreground media" + (if (saved.image != null) " artwork" else " title"),
            "Last observed while app active: " + saved.title.ifBlank { "(no title)" } +
                " | source: " + saved.source + " | " + RecentMediaStore.status(context, pkg),
        )

        val active = liveSession(context, pkg)
        if (active.bitmap != null) return active

        // Watch Next is a RECENT program listing, not the currently playing frame.
        val watch = RecentWatchNext.find(context, pkg)
        if (watch.title != null || watch.artwork != null) return Preview(
            render(context, pkg, watch.title.orEmpty(), watch.artwork),
            "Watch Next program (not verified as playing)",
            active.detail + " | " + watch.detail,
        )
        return Preview(null, "App artwork fallback",
            RecentMediaStore.status(context, pkg) + " | " + active.detail + " | " + watch.detail)
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
