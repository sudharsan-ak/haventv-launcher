package com.aktvhome.launcher.recent

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/**
 * Device-local previews: one atomic JPEG per package, max 12 apps.
 * Automatic capture is OFF until the user enables it in the lab.
 */
internal object RecentPreviewStore {
    private const val PREFS = "haven_recent_previews"
    private const val DIR = "haven_recent_previews"
    private const val ENABLED = "automatic_previews_enabled"
    private const val PREFIX = "status_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    private fun folder(context: Context) = File(context.cacheDir, DIR)

    private fun filename(pkg: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(pkg.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return hash + ".jpg"
    }

    fun file(context: Context, pkg: String): File = File(folder(context), filename(pkg))

    fun note(context: Context, pkg: String, message: String) {
        prefs(context).edit().putString(PREFIX + pkg, message).apply()
    }

    fun description(context: Context, pkg: String): String {
        val image = file(context, pkg)
        val status = prefs(context).getString(PREFIX + pkg, null)
        if (!image.exists()) return status ?: "No cached preview; use app artwork"
        val whenSaved = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(image.lastModified()))
        return "Cached preview at " + whenSaved + (if (status == null) "" else " | " + status)
    }

    fun save(context: Context, pkg: String, bitmap: Bitmap): Boolean {
        val dir = folder(context)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val dest = file(context, pkg)
        val temp = File(dir, filename(pkg) + ".tmp")
        return try {
            val written = temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            if (!written || !temp.renameTo(dest)) {
                temp.delete()
                false
            } else {
                dir.listFiles()?.filter { it.isFile && it.extension == "jpg" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(RecentPreviewPolicy.MAX_CACHED_APPS)
                    ?.forEach { it.delete() }
                note(context, pkg, "Saved " + bitmap.width + "x" + bitmap.height)
                true
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    /** A secure screenshot must not leave a stale potentially misleading preview. */
    fun clearSecure(context: Context, pkg: String) {
        file(context, pkg).delete()
        note(context, pkg, "Secure/blank content; use app artwork")
    }

    fun clearAll(context: Context) {
        folder(context).listFiles()?.forEach { it.delete() }
        prefs(context).edit().apply {
            prefs(context).all.keys.filter { it.startsWith(PREFIX) }.forEach { remove(it) }
        }.apply()
    }

    fun count(context: Context): Int = folder(context).listFiles()
        ?.count { it.isFile && it.extension == "jpg" } ?: 0
}
