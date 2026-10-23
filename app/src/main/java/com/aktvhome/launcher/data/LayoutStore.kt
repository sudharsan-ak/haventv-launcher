package com.aktvhome.launcher.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A row on the home screen. The Inputs row is one too ([Defaults.INPUTS_ID]): it holds no
 * apps, but moves, hides and takes an icon like any other.
 */
data class AppCollection(
    val id: String,
    val name: String,
    val apps: List<String>,
    /** Key into the icon set, see ui/Icons.kt. */
    val icon: String = Defaults.iconFor(id),
    /** Hidden from the home screen (only offered for Inputs today). */
    val hidden: Boolean = false,
) {
    val isInputs get() = id == Defaults.INPUTS_ID
}

data class Layout(
    val collections: List<AppCollection>,
    val hidden: Set<String>,
    /** Every package Haven has already placed once, so new installs can be told apart. */
    val known: Set<String>,
)

/** The user's collections, app order and hidden apps. Saved on every change. */
class LayoutStore(context: Context) {

    private val prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)

    var layout by mutableStateOf(load())
        private set

    /** Places apps Haven has never seen, and nothing else. */
    fun reconcile(installed: Collection<String>) {
        // Seeded apps go in the order listed in Defaults; the rest keep the given order.
        val fresh = installed.filter { it !in layout.known }.sortedBy { Defaults.seedRank(it) }
        if (fresh.isEmpty()) return
        var cols = layout.collections
        val hidden = layout.hidden.toMutableSet()
        for (pkg in fresh) {
            if (pkg in Defaults.hiddenAtStart) {
                hidden += pkg
                continue
            }
            val target = Defaults.seedFor(pkg)?.takeIf { id -> cols.any { it.id == id } }
                ?: Defaults.OTHER_ID
            cols = ensureOther(cols, target).map { c ->
                if (c.id == target) c.copy(apps = c.apps + pkg) else c
            }
        }
        commit(layout.copy(collections = cols, hidden = hidden, known = layout.known + fresh))
    }

    fun collectionOf(pkg: String): AppCollection? = layout.collections.firstOrNull { pkg in it.apps }

    /** Shifts an app left (-1) or right (+1) inside its collection. */
    fun shiftApp(pkg: String, delta: Int, visible: List<String>) {
        val col = collectionOf(pkg) ?: return
        // Move relative to the apps actually shown, so uninstalled/hidden ones don't eat key presses.
        val from = visible.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in visible.indices) return
        val neighbor = visible[to]
        val apps = col.apps.toMutableList()
        val a = apps.indexOf(pkg)
        val b = apps.indexOf(neighbor)
        if (a < 0 || b < 0) return
        apps.removeAt(a)
        apps.add(b, pkg)
        replace(col.copy(apps = apps))
    }

    fun moveToCollection(pkg: String, targetId: String) {
        val cols = layout.collections.map { c ->
            when {
                c.id == targetId && pkg !in c.apps -> c.copy(apps = c.apps + pkg)
                c.id != targetId && pkg in c.apps -> c.copy(apps = c.apps - pkg)
                else -> c
            }
        }
        commit(layout.copy(collections = cols))
    }

    fun hide(pkg: String) = commit(layout.copy(hidden = layout.hidden + pkg))

    fun unhide(pkg: String) {
        val cols = if (collectionOf(pkg) == null) {
            val target = Defaults.seedFor(pkg)?.takeIf { id -> layout.collections.any { it.id == id } }
                ?: Defaults.OTHER_ID
            ensureOther(layout.collections, target).map { c ->
                if (c.id == target) c.copy(apps = c.apps + pkg) else c
            }
        } else layout.collections
        commit(layout.copy(collections = cols, hidden = layout.hidden - pkg))
    }

    fun addCollection(name: String, icon: String = Defaults.NEW_ICON): String {
        val id = UUID.randomUUID().toString()
        commit(layout.copy(collections = layout.collections + AppCollection(id, name.trim(), emptyList(), icon)))
        return id
    }

    fun setIcon(id: String, icon: String) {
        layout.collections.firstOrNull { it.id == id }?.let { replace(it.copy(icon = icon)) }
    }

    fun setCollectionHidden(id: String, hidden: Boolean) {
        layout.collections.firstOrNull { it.id == id }?.let { replace(it.copy(hidden = hidden)) }
    }

    fun renameCollection(id: String, name: String) {
        if (name.isBlank()) return
        layout.collections.firstOrNull { it.id == id }?.let { replace(it.copy(name = name.trim())) }
    }

    /** Deletes a collection; its apps go to Other so nothing is lost. */
    fun deleteCollection(id: String) {
        val doomed = layout.collections.firstOrNull { it.id == id } ?: return
        if (doomed.isInputs) return
        if (id == Defaults.OTHER_ID && doomed.apps.isNotEmpty()) return
        var cols = layout.collections.filter { it.id != id }
        if (doomed.apps.isNotEmpty()) {
            cols = ensureOther(cols, Defaults.OTHER_ID).map { c ->
                if (c.id == Defaults.OTHER_ID) c.copy(apps = c.apps + doomed.apps) else c
            }
        }
        commit(layout.copy(collections = cols))
    }

    fun shiftCollection(id: String, delta: Int) {
        val cols = layout.collections.toMutableList()
        val from = cols.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in cols.indices) return
        cols.add(to, cols.removeAt(from))
        commit(layout.copy(collections = cols))
    }

    private fun ensureOther(cols: List<AppCollection>, target: String): List<AppCollection> =
        if (target == Defaults.OTHER_ID && cols.none { it.id == Defaults.OTHER_ID })
            cols + AppCollection(Defaults.OTHER_ID, Defaults.OTHER_NAME, emptyList())
        else cols

    private fun replace(col: AppCollection) =
        commit(layout.copy(collections = layout.collections.map { if (it.id == col.id) col else it }))

    private fun commit(next: Layout) {
        layout = next
        prefs.edit().putString(KEY, toJson(next).toString()).apply()
    }

    private fun load(): Layout {
        val raw = prefs.getString(KEY, null)
        val saved = raw?.let { runCatching { fromJson(JSONObject(raw)) }.getOrNull() }
        val l = saved ?: Layout(
            collections = Defaults.seeds.map { AppCollection(it.id, it.name, emptyList()) },
            hidden = emptySet(),
            known = emptySet(),
        )
        // Layouts saved before Inputs became a row get it appended at the end.
        return if (l.collections.any { it.isInputs }) l
        else l.copy(collections = l.collections + AppCollection(Defaults.INPUTS_ID, Defaults.INPUTS_NAME, emptyList()))
    }

    private fun toJson(l: Layout) = JSONObject().apply {
        put("collections", JSONArray().apply {
            l.collections.forEach { c ->
                put(
                    JSONObject().put("id", c.id).put("name", c.name).put("apps", JSONArray(c.apps))
                        .put("icon", c.icon).put("hidden", c.hidden),
                )
            }
        })
        put("hidden", JSONArray(l.hidden.toList()))
        put("known", JSONArray(l.known.toList()))
    }

    private fun fromJson(o: JSONObject): Layout {
        val cols = o.getJSONArray("collections").let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val id = c.getString("id")
                AppCollection(
                    id, c.getString("name"), c.getJSONArray("apps").strings(),
                    icon = c.optString("icon", Defaults.iconFor(id)),
                    hidden = c.optBoolean("hidden", false),
                )
            }
        }
        return Layout(cols, o.getJSONArray("hidden").strings().toSet(), o.getJSONArray("known").strings().toSet())
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private companion object {
        const val KEY = "layout_v1"
    }
}
