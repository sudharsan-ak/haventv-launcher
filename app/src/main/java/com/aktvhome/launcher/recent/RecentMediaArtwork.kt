package com.aktvhome.launcher.recent

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import com.aktvhome.launcher.HavenMediaListener

/**
 * Optional metadata-based artwork for apps whose video surfaces are not capturable.
 * Uses Android's existing, user-enabled Notification Listener; never fetches remote
 * image URLs and never tries to read a protected video buffer.
 */
internal object RecentMediaArtwork {
    data class Preview(val bitmap: Bitmap?, val label: String, val detail: String)

    fun find(context: Context, pkg: String): Preview {
        val sessions = try {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            manager.getActiveSessions(ComponentName(context, HavenMediaListener::class.java))
        } catch (error: Exception) {
            return Preview(null, "App artwork fallback",
                "Media sessions unavailable: " + error.javaClass.simpleName)
        }
        val session = sessions.firstOrNull { it.packageName == pkg }
            ?: return Preview(null, "App artwork fallback", "No active media session for this app")
        val metadata = session.metadata
            ?: return Preview(null, "App artwork fallback", "Session has no media metadata")
        val title = (
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.description?.title?.toString()
            )?.trim()?.take(100).orEmpty()
        val art = try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        } catch (_: Exception) {
            null
        }
        if (art == null && title.isBlank()) {
            return Preview(null, "App artwork fallback", "Media session does not publish title or bitmap artwork")
        }
        return try {
            val out = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.rgb(17, 24, 31))
            if (art != null && art.width > 0 && art.height > 0) {
                val safe = if (art.config == Bitmap.Config.HARDWARE)
                    art.copy(Bitmap.Config.ARGB_8888, false) else art
                if (safe != null) {
                    val factor = maxOf(480f / safe.width, 270f / safe.height)
                    val w = safe.width * factor
                    val h = safe.height * factor
                    canvas.drawBitmap(safe, null,
                        RectF((480f - w) / 2f, (270f - h) / 2f,
                            (480f + w) / 2f, (270f + h) / 2f), null)
                    if (safe !== art) safe.recycle()
                }
            } else {
                drawAppIcon(context, pkg, canvas)
            }
            if (title.isNotBlank()) {
                val shade = Paint().apply {
                    shader = LinearGradient(0f, 140f, 0f, 270f, 0x00000000,
                        0xe9000000.toInt(), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 140f, 480f, 270f, shade)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 23f
                    isFakeBoldText = true
                }
                val display = if (title.length > 39) title.take(36) + "..." else title
                canvas.drawText(display, 18f, 245f, paint)
            }
            Preview(out, if (art != null) "Media session artwork" else "Media title + app icon",
                "Media session: " + (if (title.isBlank()) "artwork (no title)" else title))
        } catch (error: Exception) {
            Preview(null, "App artwork fallback",
                "Media artwork could not render: " + error.javaClass.simpleName)
        }
    }

    /** Use Android-provided application artwork only, never a fabricated screenshot. */
    fun appFallback(context: Context, pkg: String): Bitmap? = try {
        val out = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(18, 28, 34))
        val banner = context.packageManager.getApplicationBanner(pkg)
        val drawable = banner ?: context.packageManager.getApplicationIcon(pkg)
        if (banner == null) drawable.setBounds(176, 71, 304, 199)
        else drawable.setBounds(0, 0, 480, 270)
        drawable.draw(canvas)
        out
    } catch (_: Exception) {
        null
    }

    private fun drawAppIcon(context: Context, pkg: String, canvas: Canvas) {
        try {
            val icon = context.packageManager.getApplicationIcon(pkg)
            icon.setBounds(176, 58, 304, 186)
            icon.draw(canvas)
        } catch (_: Exception) {
            // A title alone is still a useful media fallback.
        }
    }
}
