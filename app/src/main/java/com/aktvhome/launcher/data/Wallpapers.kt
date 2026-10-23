package com.aktvhome.launcher.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.random.Random

/**
 * Rotating photo backgrounds. Fetches one photo at a time from the enabled sources, keeps the
 * last [KEEP] on the TV so a slow or missing connection never leaves a blank screen, and only
 * works while Haven is open: [tick] is called from the UI clock, nothing runs in the background.
 */
class Wallpapers(context: Context, private val scope: CoroutineScope, private val settingsStore: SettingsStore) {

    private val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)

    var current by mutableStateOf<ImageBitmap?>(null)
        private set
    /** e.g. "Autumn in Naejangsan National Park · Bing". */
    var credit by mutableStateOf(prefs.getString("credit", "") ?: "")
        private set
    /** One line for the settings page: what happened last. */
    var status by mutableStateOf("")
        private set

    private var busy = false
    private var retryAt = 0L

    fun lastChange(): Long = prefs.getLong("lastChange", 0L)

    /** Called every ~15 s while Haven is open. [force] = "Next wallpaper now". */
    fun tick(force: Boolean = false) {
        val s = settingsStore.settings
        val wanted = s.bgKingdom == Background.PHOTO || s.bgTide == Background.PHOTO
        if (!wanted && !force) return
        if (current == null) loadSaved()
        val now = System.currentTimeMillis()
        val due = force || current == null || now - lastChange() >= s.wallInterval.millis
        if (!due || busy || (!force && now < retryAt)) return
        busy = true
        status = "Fetching a new photo…"
        scope.launch(Dispatchers.IO) {
            try {
                fetchNext(s)
            } catch (e: Exception) {
                retryAt = System.currentTimeMillis() + RETRY_MS
                status = "Couldn't fetch a photo (${e.message ?: e.javaClass.simpleName}). Retrying in 10 minutes."
            } finally {
                busy = false
            }
        }
    }

    private fun loadSaved() {
        val name = prefs.getString("file", null) ?: return
        val f = File(dir, name)
        if (f.exists()) current = decode(f.readBytes())
    }

    private suspend fun fetchNext(s: Settings) {
        val sources = s.wallSources.filter { it != WallSource.UNSPLASH || s.unsplashKey.isNotBlank() }
        if (sources.isEmpty()) {
            status = "No sources switched on."
            return
        }
        val recent = recentUrls()
        var pick: Candidate? = null
        // A source can come up empty (all recently shown, odd-shaped picture); try the others.
        for (src in sources.shuffled()) {
            pick = runCatching { candidate(src, s, recent) }.getOrNull()
            if (pick != null) break
        }
        val c = pick ?: throw IllegalStateException("no photo available")
        val bytes = get(c.url, c.headers)
        val bmp = decode(bytes) ?: throw IllegalStateException("unreadable image")
        val name = "w_${System.currentTimeMillis()}.jpg"
        File(dir, name).writeBytes(bytes)
        c.ping?.let { runCatching { get(it, c.headers) } } // Unsplash asks to be told when a photo is used.
        withContext(Dispatchers.Main) {
            current = bmp
            credit = c.credit
        }
        prefs.edit()
            .putString("file", name).putString("credit", c.credit)
            .putLong("lastChange", System.currentTimeMillis())
            .putString("recent", JSONArray((recent + c.url).takeLast(RECENT)).toString())
            .apply()
        prune()
        status = "Updated: ${c.credit}"
    }

    private data class Candidate(val url: String, val credit: String, val ping: String? = null, val headers: Map<String, String> = emptyMap())

    private fun candidate(src: WallSource, s: Settings, recent: List<String>): Candidate? = when (src) {
        WallSource.BING -> {
            val images = JSONObject(text(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US")))
                .getJSONArray("images")
            (0 until images.length()).map { images.getJSONObject(it) }
                .map { Candidate("https://www.bing.com" + it.getString("url"), it.optString("copyright").substringBefore(" (©").trim() + " · Bing") }
                .filter { it.url !in recent }.randomOrNull()
        }
        WallSource.EARTH -> {
            val list = earthList()
            val fresh = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.getString("image") !in recent }
            fresh.randomOrNull()?.let { e ->
                val place = listOf(e.optString("region"), e.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
                Candidate(e.getString("image"), "${place.ifBlank { "Earth" }} · Google Earth View")
            }
        }
        WallSource.WIKI -> wikiCandidate(recent)
        WallSource.UNSPLASH -> {
            val topic = WALL_TOPICS.filter { it.first in s.wallTopics }.ifEmpty { WALL_TOPICS.take(1) }.random()
            val headers = mapOf("Authorization" to "Client-ID ${s.unsplashKey.trim()}", "Accept-Version" to "v1")
            val q = URLEncoder.encode(topic.second, "UTF-8")
            val o = JSONObject(text(get("https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&query=$q", headers)))
            val raw = o.getJSONObject("urls").getString("raw")
            val who = o.getJSONObject("user").optString("name", "Unknown")
            Candidate(
                "$raw&w=1920&h=1080&fit=crop&fm=jpg&q=80",
                "Photo by $who on Unsplash · ${topic.first}",
                ping = o.getJSONObject("links").optString("download_location").ifBlank { null },
                headers = headers,
            )
        }
    }

    /** Wikimedia's Picture of the Day, from a random recent day, landscape and TV-sized only. */
    private fun wikiCandidate(recent: List<String>): Candidate? {
        repeat(6) {
            val d = LocalDate.now().minusDays(Random.nextLong(1, 90))
            val url = "https://api.wikimedia.org/feed/v1/wikipedia/en/featured/%d/%02d/%02d".format(d.year, d.monthValue, d.dayOfMonth)
            val img = JSONObject(text(get(url))).optJSONObject("image") ?: return@repeat
            val full = img.optJSONObject("image") ?: return@repeat
            val w = full.optInt("width")
            val h = full.optInt("height")
            if (w < 1600 || h == 0 || w.toFloat() / h < 1.3f) return@repeat
            // Ask Wikimedia's thumbnailer for a 1920-wide copy instead of the (often huge) original.
            val src = img.optJSONObject("thumbnail")?.optString("source")
                ?.replace(Regex("/\\d+px-"), "/${minOf(w, 1920)}px-") ?: full.getString("source")
            if (src in recent) return@repeat
            val title = img.optString("title").removePrefix("File:").substringBeforeLast('.')
            return Candidate(src, "$title · Wikimedia Commons")
        }
        return null
    }

    /** Earth View's photo list, cached on the TV for a week. */
    private fun earthList(): JSONArray {
        val f = File(dir, "earthview.json")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 24 * 3_600_000L
        if (!fresh) f.writeText(text(get(EARTH_LIST)))
        return JSONArray(f.readText())
    }

    /** Recently shown photo URLs, oldest first. */
    private fun recentUrls(): List<String> =
        runCatching { JSONArray(prefs.getString("recent", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrDefault(emptyList())

    /** Keeps the newest [KEEP] photos. */
    private fun prune() {
        dir.listFiles { f -> f.name.startsWith("w_") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** Decodes at no more than screen size, so a 4K source costs no more memory than 1080p. */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "HavenTV/1.0 (personal TV launcher)")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code from ${URL(url).host}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    private companion object {
        const val KEEP = 20
        const val RECENT = 60
        const val RETRY_MS = 10 * 60_000L
        // Google retired its own list; this public copy of it points at Google's image servers.
        const val EARTH_LIST = "https://raw.githubusercontent.com/limhenry/earthview/master/earthview.json"
    }
}
