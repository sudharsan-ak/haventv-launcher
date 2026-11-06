package com.aktvhome.launcher.recent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/** Last metadata actually observed while the owning app was foreground. */
internal object RecentMediaStore {
    private const val PREFS = "haven_recent_media"
    private const val DIR = "haven_recent_media"
    private const val MAX_AGE_MS = 30 * 60_000L
    private const val MAX_APPS = 12
    private const val STATUS = "status_"
    private const val TITLE = "title_"
    private const val SOURCE = "source_"
    private const val UPDATED = "updated_"

    data class Item(
        val title: String, val image: Bitmap?, val source: String,
        val capturedAt: Long,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(context: Context) = File(context.cacheDir, DIR)
    private fun file(context: Context, pkg: String): File {
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(pkg.toByteArray(Charsets.UTF_8)).take(16)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(dir(context), "$hex.jpg")
    }

    fun status(context: Context, pkg: String): String {
        val p = prefs(context)
        val at = p.getLong(UPDATED + pkg, 0L)
        val time = if (at <= 0) "never" else DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.MEDIUM).format(Date(at))
        val title = p.getString(TITLE + pkg, "").orEmpty()
        return "Media last observed: $time | Title: " +
            (if (title.isBlank()) "(none)" else title.take(60)) +
            " | " + p.getString(STATUS + pkg, "No media probe yet")
    }

    fun note(context: Context, pkg: String, message: String) {
        prefs(context).edit().putString(STATUS + pkg, message.take(160)).apply()
    }

    /** Called on one IO worker, not from the Accessibility event thread. */
    fun remember(context: Context, pkg: String, title: String,
                 artwork: Bitmap?, source: String): Boolean {
        if (title.isBlank() && artwork == null) return false
        val cleanTitle = title.trim().take(100)
        val previous = prefs(context).getString(TITLE + pkg, "").orEmpty()
        val imageFile = file(context, pkg)
        val folder = dir(context)
        if (!folder.isDirectory && !folder.mkdirs()) {
            note(context, pkg, "Could not create artwork cache")
            return false
        }
        if (artwork != null) {
            val temporary = File(folder, imageFile.name + ".tmp")
            val saved = try {
                val done = temporary.outputStream().use {
                    artwork.compress(Bitmap.CompressFormat.JPEG, 80, it)
                }
                done && temporary.renameTo(imageFile)
            } catch (_: Exception) {
                false
            }
            temporary.delete()
            if (!saved) {
                note(context, pkg, "Artwork write failed; prior metadata retained")
                return false
            }
        } else if (previous != cleanTitle) {
            // A title changed but the new session did not publish artwork.
            imageFile.delete()
        }
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString(TITLE + pkg, cleanTitle)
            .putString(SOURCE + pkg, source)
            .putLong(UPDATED + pkg, now)
            .putString(STATUS + pkg, "Foreground media metadata: " + source)
            .apply()
        // Bound on-disk artwork without deleting the text-only metadata.
        folder.listFiles()?.filter { it.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }?.drop(MAX_APPS)
            ?.forEach { it.delete() }
        return true
    }

    fun get(context: Context, pkg: String): Item? {
        val p = prefs(context)
        val time = p.getLong(UPDATED + pkg, 0L)
        if (time == 0L || System.currentTimeMillis() - time !in 0..MAX_AGE_MS) return null
        val title = p.getString(TITLE + pkg, "").orEmpty()
        val picture = file(context, pkg).takeIf { it.isFile }
            ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        if (title.isBlank() && picture == null) return null
        return Item(title, picture, p.getString(SOURCE + pkg, "media metadata").orEmpty(), time)
    }

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        prefs(context).edit().clear().apply()
    }
}
