package com.aktvhome.launcher.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps a copy of everything you've set up in Download/Haven, outside Haven's own data folder,
 * so a reinstall, a move between drives or a reset can't lose it.
 *
 * - [tick] (every ~15 s) writes the backup whenever something changed; the previous copy is
 *   kept as haven-backup-prev.json.
 * - On a fresh start (no settings yet) with a backup on disk, automatic backup pauses and
 *   [offerRestore] is set, so an empty Haven never overwrites a good backup.
 *
 * The Cleanup record (what Haven switched off, and each package's state from before) is backed up
 * too, so it survives a reinstall. Restoring merges it rather than replacing it, and Reset leaves
 * it alone: it's what puts switched-off packages back.
 *
 * Reading a backup written by an earlier install needs "All files access", granted once over ADB.
 */
class Backup(private val context: Context, private val onRestored: () -> Unit) {

    private val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Haven")
    private val file = File(dir, "haven-backup.json")
    private val prev = File(dir, "haven-backup-prev.json")
    private val meta = context.getSharedPreferences("backup_meta", Context.MODE_PRIVATE)

    /** Set when Haven starts with no settings but a backup exists; the home screen offers to restore. */
    var offerRestore by mutableStateOf(false)
        private set
    var lastSaved by mutableStateOf(meta.getLong("lastSaved", 0L))
        private set
    /** Last problem writing or reading, shown in settings; "" when fine. */
    var problem by mutableStateOf("")
        private set

    private var lastWritten: String? = null

    init {
        val fresh = PREFS.none { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isNotEmpty() }
        if (fresh && !meta.getBoolean("decided", false) && runCatching { file.exists() }.getOrDefault(false)) {
            offerRestore = true
        } else {
            meta.edit().putBoolean("decided", true).apply()
        }
    }

    fun canAccessFiles(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    fun backupExists(): Boolean = runCatching { file.exists() }.getOrDefault(false)

    fun backupTime(): Long = runCatching { JSONObject(file.readText()).optLong("savedAt") }.getOrDefault(0L)

    /** Writes the backup if anything changed since the last write. */
    fun tick() {
        if (offerRestore) return
        val snapshot = snapshot()
        if (snapshot == lastWritten) return
        if (write(snapshot)) lastWritten = snapshot
    }

    fun backupNow(): Boolean {
        val s = snapshot()
        return write(s).also { if (it) lastWritten = s }
    }

    /** Replaces Haven's settings with the backup. Returns false if there's no readable backup. */
    fun restore(): Boolean {
        val json = runCatching { JSONObject(file.readText()) }.getOrElse {
            problem = if (!canAccessFiles()) "Haven can't read the backup yet (needs All files access)." else "Couldn't read the backup."
            return false
        }
        val prefs = json.optJSONObject("prefs") ?: return false
        for (name in BACKED_UP) {
            val values = prefs.optJSONObject(name) ?: continue
            val target = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val merge = name in MERGED
            val editor = if (merge) target.edit() else target.edit().clear()
            for (key in values.keys()) {
                if (merge && target.contains(key)) continue
                val v = values.getJSONObject(key)
                when (v.getString("t")) {
                    "s" -> editor.putString(key, v.getString("v"))
                    "b" -> editor.putBoolean(key, v.getBoolean("v"))
                    "i" -> editor.putInt(key, v.getInt("v"))
                    "l" -> editor.putLong(key, v.getLong("v"))
                    "f" -> editor.putFloat(key, v.getDouble("v").toFloat())
                    "set" -> editor.putStringSet(key, v.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                }
            }
            editor.commit()
        }
        decided()
        lastWritten = null
        problem = ""
        onRestored()
        return true
    }

    /** Fresh start chosen over restoring: resume automatic backups (the old file is rotated, not lost). */
    fun startFresh() = decided()

    /** Clears every setting back to first-run. The backup files stay, so this can be undone with Restore. */
    fun reset() {
        backupNow()
        PREFS.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        lastWritten = null
        onRestored()
    }

    private fun decided() {
        offerRestore = false
        meta.edit().putBoolean("decided", true).apply()
    }

    private fun write(snapshot: String): Boolean = runCatching {
        dir.mkdirs()
        if (file.exists()) file.copyTo(prev, overwrite = true)
        val tmp = File(dir, "haven-backup.tmp")
        tmp.writeText(JSONObject(snapshot).put("savedAt", System.currentTimeMillis()).toString(2))
        tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    }.fold(
        onSuccess = { ok ->
            if (ok) {
                lastSaved = System.currentTimeMillis()
                meta.edit().putLong("lastSaved", lastSaved).apply()
                problem = ""
            } else problem = "Couldn't save the backup file."
            ok
        },
        onFailure = {
            problem = if (!canAccessFiles()) "Haven can't write to Download/Haven yet (needs All files access)." else "Couldn't save: ${it.message}"
            false
        },
    )

    /** Every saved preference as JSON, without the time stamp, so unchanged settings compare equal. */
    private fun snapshot(): String {
        val prefs = JSONObject()
        for (name in BACKED_UP) prefs.put(name, encode(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        return JSONObject().put("app", "Haven").put("version", 1).put("prefs", prefs).toString()
    }

    private fun encode(p: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in p.all.toSortedMap()) {
            val e = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }.sorted()))
                else -> null
            } ?: continue
            o.put(k, e)
        }
        return o
    }

    private companion object {
        /** Haven's saved settings: theme, wallpaper, DNS, Watch Now… and the home layout. */
        val PREFS = listOf("settings", "layout")

        /** What the backup file keeps: the settings and the Cleanup record. */
        val BACKED_UP = PREFS + CleanupRunner.PREFS

        /** Restored by adding what's missing, never by clearing what's there. */
        val MERGED = setOf(CleanupRunner.PREFS)
    }
}
