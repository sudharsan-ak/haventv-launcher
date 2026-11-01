package com.aktvhome.launcher.data

import android.content.Context
import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private val customFile = File(dir, "custom_wallpaper.jpg")

    private var rotatingPhoto by mutableStateOf<ImageBitmap?>(null)
    var customPhoto by mutableStateOf<ImageBitmap?>(null)
        private set

    /** One shared wallpaper for Kingdom and Tide, selected by source setting. */
    val current: ImageBitmap?
        get() = if (settingsStore.settings.wallpaperMode == WallpaperMode.MY_PHOTO) {
            customPhoto ?: rotatingPhoto
        } else rotatingPhoto

    private var rotatingCredit by mutableStateOf(prefs.getString("credit", "") ?: "")
    val credit: String
        get() = if (settingsStore.settings.wallpaperMode == WallpaperMode.MY_PHOTO && customPhoto != null)
            "My photo" else rotatingCredit

    data class PreparedPhoto(val jpegBytes: ByteArray, val image: ImageBitmap, val name: String)
    var pendingPhoto by mutableStateOf<PreparedPhoto?>(null)
        private set

    init {
        customPhoto = runCatching { if (customFile.exists()) decode(customFile.readBytes()) else null }.getOrNull()
    }
    /** One line for the settings page: what happened last. */
    var status by mutableStateOf("")
        private set

    private var busy = false
    private var retryAt = 0L

    fun lastChange(): Long = prefs.getLong("lastChange", 0L)

    /** The online photo remains cached when switching to a custom wallpaper and back. */
    fun setMode(mode: WallpaperMode) {
        if (mode == settingsStore.settings.wallpaperMode) return
        prefs.edit()
            .putLong("rotationRevision", prefs.getLong("rotationRevision", 0L) + 1)
            .putLong("lastChange", System.currentTimeMillis())
            .apply()
        settingsStore.update { it.copy(wallpaperMode = mode) }
        status = if (mode == WallpaperMode.MY_PHOTO && customPhoto == null)
            "Choose a photo from the TV or send one from a device."
        else if (mode == WallpaperMode.MY_PHOTO) "Using your photo." else "Rotating photos resumed."
        if (mode == WallpaperMode.ROTATING) tick()
    }

    /** Stage one validated, resized image. This does not alter the current wallpaper. */
    suspend fun prepare(bytes: ByteArray, name: String): Boolean {
        val photo = withContext(Dispatchers.IO) {
            if (bytes.isEmpty() || bytes.size > MAX_IMPORT_BYTES) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000)
                require(bounds.outWidth.toLong() * bounds.outHeight <= 60_000_000L)
                var sample = 1
                while (bounds.outWidth / sample > 3840 || bounds.outHeight / sample > 2160) sample *= 2
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Unreadable image")
                val scale = minOf(1f, 1920f / decoded.width, 1080f / decoded.height)
                val width = (decoded.width * scale).toInt().coerceAtLeast(1)
                val height = (decoded.height * scale).toInt().coerceAtLeast(1)
                val optimized = if (width != decoded.width || height != decoded.height)
                    Bitmap.createScaledBitmap(decoded, width, height, true) else decoded
                val output = ByteArrayOutputStream()
                require(optimized.compress(Bitmap.CompressFormat.JPEG, 88, output))
                if (optimized !== decoded) decoded.recycle()
                PreparedPhoto(output.toByteArray(), optimized.asImageBitmap(), name.take(80))
            }.getOrNull()
        }
        if (photo == null) {
            status = "Couldn't import the photo. Use JPG, PNG or WebP under 16 MB."
            return false
        }
        pendingPhoto = photo
        status = "Photo ready to preview."
        return true
    }

    fun cancelPending() { pendingPhoto = null }

    /** Atomic replacement leaves the old custom wallpaper intact if an import fails. */
    suspend fun applyPending(fit: WallpaperFit): Boolean {
        val prepared = pendingPhoto ?: return false
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(dir, "custom_wallpaper.tmp")
                tmp.writeBytes(prepared.jpegBytes)
                try {
                    Files.move(tmp.toPath(), customFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    tmp.delete()
                }
            }.isSuccess
        }
        if (!saved) {
            status = "Couldn't save the photo. Your old wallpaper is unchanged."
            return false
        }
        customPhoto = prepared.image
        pendingPhoto = null
        prefs.edit().putLong("rotationRevision", prefs.getLong("rotationRevision", 0L) + 1).apply()
        settingsStore.update { s ->
            s.copy(
                wallpaperMode = WallpaperMode.MY_PHOTO,
                wallpaperFit = fit,
                bgKingdom = if (s.theme == ThemeId.KINGDOM) Background.PHOTO else s.bgKingdom,
                bgTide = if (s.theme == ThemeId.TIDE) Background.PHOTO else s.bgTide,
            )
        }
        status = "Your photo is now the wallpaper."
        return true
    }

    /** Backup restore changes the image file outside settings, so refresh both caches. */
    fun reload() {
        rotatingPhoto = null
        loadSaved()
        customPhoto = runCatching { if (customFile.exists()) decode(customFile.readBytes()) else null }.getOrNull()
        pendingPhoto = null
    }

    /** Invalidates an in-flight download, including when pin is turned off before it finishes. */
    fun setPinned(pinned: Boolean) {
        val revision = prefs.getLong("rotationRevision", 0L) + 1L
        prefs.edit().putLong("rotationRevision", revision).apply()
        settingsStore.update { it.copy(wallpaperPinned = pinned) }
        if (!pinned) {
            // Start the next interval now, without immediately replacing the retained photo.
            prefs.edit().putLong("lastChange", System.currentTimeMillis()).apply()
            retryAt = 0L
            status = "Rotation resumed. The current photo stays until the next interval."
        } else {
            status = "Current photo pinned. Rotation paused."
        }
    }

    /** Called every ~15 s while Haven is open. [force] = "Next wallpaper now". */
    fun tick(force: Boolean = false) {
        val s = settingsStore.settings
        val wanted = s.bgKingdom == Background.PHOTO || s.bgTide == Background.PHOTO
        if (!wanted && !force) return
        if (rotatingPhoto == null) loadSaved()
        // The imported wallpaper never rotates; if it is missing, the saved online
        // photo remains a safe fallback rather than showing a blank screen.
        if (s.wallpaperMode == WallpaperMode.MY_PHOTO && customPhoto != null) return
        // Pinning always wins, even over the explicit Next photo action.
        if (s.wallpaperPinned && s.wallpaperMode == WallpaperMode.ROTATING) return
        val now = System.currentTimeMillis()
        val due = force || rotatingPhoto == null || now - lastChange() >= s.wallInterval.millis
        if (!due || busy || (!force && now < retryAt)) return
        busy = true
        status = "Fetching a new photo…"
        val revision = prefs.getLong("rotationRevision", 0L)
        scope.launch(Dispatchers.IO) {
            try {
                fetchNext(s, revision)
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
        if (f.exists()) rotatingPhoto = decode(f.readBytes())
    }

    private suspend fun fetchNext(s: Settings, revision: Long) {
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
        // If the user pins the current wallpaper during a download, discard the fetched image.
        // The final check runs on Main, where settings changes and the visible photo are updated.
        if (settingsStore.settings.wallpaperPinned ||
            prefs.getLong("rotationRevision", 0L) != revision) return
        val name = "w_${System.currentTimeMillis()}.jpg"
        val file = File(dir, name)
        file.writeBytes(bytes)
        val accepted = withContext(Dispatchers.Main) {
            if (settingsStore.settings.wallpaperPinned ||
                prefs.getLong("rotationRevision", 0L) != revision) {
                if (settingsStore.settings.wallpaperPinned) status = "Current photo pinned. Rotation paused."
                false
            } else {
                rotatingPhoto = bmp
                rotatingCredit = c.credit
                prefs.edit()
                    .putString("file", name).putString("credit", c.credit)
                    .putLong("lastChange", System.currentTimeMillis())
                    .putString("recent", JSONArray((recent + c.url).takeLast(RECENT)).toString())
                    .apply()
                status = "Updated: ${c.credit}"
                true
            }
        }
        if (!accepted) {
            file.delete()
            return
        }
        c.ping?.let { runCatching { get(it, c.headers) } } // Unsplash asks to be told when a photo is used.
        prune()
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
        const val MAX_IMPORT_BYTES = 16 * 1024 * 1024
        const val KEEP = 20
        const val RECENT = 60
        const val RETRY_MS = 10 * 60_000L
        // Google retired its own list; this public copy of it points at Google's image servers.
        const val EARTH_LIST = "https://raw.githubusercontent.com/limhenry/earthview/master/earthview.json"
    }
}
