package com.aktvhome.launcher.recent

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aktvhome.launcher.HavenMediaListener
import com.aktvhome.launcher.data.NbaAlerts
import java.util.concurrent.Executors

/**
 * Observes only opted-in, foreground app sessions using Haven's existing notification access.
 * Captures metadata BEFORE apps go into the background; never captures protected video.
 */
internal object RecentMediaCollector {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HavenRecentMedia").apply { isDaemon = true }
    }
    private var context: Context? = null
    private var manager: MediaSessionManager? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val registered = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var foreground: String? = null
    private var lastRefresh = 0L
    private var lastAttempt = 0L
    private val probeTimes = mutableMapOf<String, Long>()
    private val recentSignatures = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    fun currentForeground(): String? = foreground

    fun onForeground(service: AccessibilityService, pkg: String) {
        if (!RecentPreviewStore.enabled(service)) {
            stop()
            return
        }
        val switched = pkg != foreground
        foreground = pkg
        if (!ensureStarted(service)) return
        val now = SystemClock.elapsedRealtime()
        if (switched || now - lastRefresh > 5_000L) refreshControllers()
        // Active media sessions can already exist when app enters foreground.
        if (switched) sampleCurrent(pkg, force = true)
    }

    fun beforeHome(service: AccessibilityService, pkg: String) {
        if (!RecentPreviewStore.enabled(service)) return
        if (foreground == pkg) sampleCurrent(pkg, force = true)
    }

    fun leaveForeground() {
        foreground = null
    }

    private fun ensureStarted(service: AccessibilityService): Boolean {
        if (manager != null) return true
        val ctx = service.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 5_000L) return false
        lastAttempt = now
        if (!NbaAlerts.canSeePlaying(ctx)) {
            foreground?.let {
                RecentMediaStore.note(ctx, it, "Notification access missing; media sessions unavailable")
            }
            return false
        }
        return try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                bindControllers(controllers.orEmpty())
            }
            m.addOnActiveSessionsChangedListener(listener,
                ComponentName(ctx, HavenMediaListener::class.java), main)
            context = ctx
            manager = m
            sessionListener = listener
            refreshControllers()
            true
        } catch (e: Exception) {
            foreground?.let {
                RecentMediaStore.note(ctx, it, "Media listener unavailable: " + e.javaClass.simpleName)
            }
            false
        }
    }

    private fun refreshControllers() {
        val ctx = context ?: return
        val m = manager ?: return
        val listenerComponent = ComponentName(ctx, HavenMediaListener::class.java)
        lastRefresh = SystemClock.elapsedRealtime()
        io.execute {
            val result = runCatching { m.getActiveSessions(listenerComponent) }
            main.post {
                if (manager !== m) return@post
                result.onSuccess { bindControllers(it) }
                    .onFailure {
                        foreground?.let { pkg ->
                            RecentMediaStore.note(ctx, pkg,
                                "Media session query failed: " + it.javaClass.simpleName)
                        }
                    }
            }
        }
    }

    private fun bindControllers(controllers: List<MediaController>) {
        val valid = controllers.map { it.sessionToken }.toSet()
        registered.keys.filter { it !in valid }.toList().forEach { token ->
            registered.remove(token)?.let { (controller, callback) ->
                runCatching { controller.unregisterCallback(callback) }
            }
        }
        controllers.forEach { controller ->
            val token = controller.sessionToken
            if (registered.containsKey(token)) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (metadata != null && controller.packageName == foreground)
                        schedule(controller, "Media metadata changed")
                }

                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                    if (controller.packageName == foreground) schedule(controller, "Playback state changed")
                }
            }
            runCatching {
                controller.registerCallback(cb, main)
                registered[token] = controller to cb
            }
        }
        foreground?.let { sampleCurrent(it, force = true) }
    }

    private fun sampleCurrent(pkg: String, force: Boolean) {
        val matching = registered.values.map { it.first }.filter { it.packageName == pkg }
        if (matching.isEmpty()) {
            context?.let { RecentMediaStore.note(it, pkg, "No active MediaSession for foreground app") }
        } else {
            matching.firstOrNull { it.playbackState?.state ==
                android.media.session.PlaybackState.STATE_PLAYING }?.let {
                schedule(it, "Foreground playback", force)
            } ?: schedule(matching.first(), "Foreground media", force)
        }
    }

    private fun schedule(controller: MediaController, reason: String, force: Boolean = false) {
        val ctx = context ?: return
        val pkg = controller.packageName
        if (pkg != foreground || !RecentPreviewStore.enabled(ctx)) return
        val now = SystemClock.elapsedRealtime()
        val last = probeTimes[pkg] ?: 0L
        if (!force && now - last < 3_000L) return
        if (force && now - last < 700L) return
        probeTimes[pkg] = now
        io.execute {
            if (!RecentPreviewStore.enabled(ctx)) return@execute
            val metadata = runCatching { controller.metadata }.getOrNull()
            if (metadata == null) {
                RecentMediaStore.note(ctx, pkg, "Media session found, metadata not published")
                return@execute
            }
            val title = listOfNotNull(
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                metadata.description?.title?.toString(),
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val bitmap = runCatching {
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    ?: metadata.description?.iconBitmap
            }.getOrNull()
            val uris = listOfNotNull(
                metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                metadata.description?.iconUri?.toString(),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            ).filter { it.isNotBlank() }.distinct()
            val signature = title + "|" + uris.joinToString("|") + "|" +
                (bitmap?.generationId?.toString().orEmpty())
            val last = recentSignatures[pkg]
            val time = System.currentTimeMillis()
            if (last != null && last.first == signature && time - last.second < 60_000L) return@execute
            var art = RecentArtworkLoader.scale(bitmap)
            var source = if (art == null) "title only" else "MediaSession bitmap"
            if (art == null) {
                for (uri in uris) {
                    art = RecentArtworkLoader.load(ctx, uri)
                    if (art != null) {
                        source = "MediaSession artwork URI (" +
                            android.net.Uri.parse(uri).scheme.orEmpty() + ")"
                        break
                    }
                }
            }
            if (art == null && uris.isNotEmpty()) {
                source = "Title only; artwork URI unavailable"
            }
            if (title.isBlank() && art == null) {
                RecentMediaStore.note(ctx, pkg,
                    if (uris.isEmpty()) "MediaSession has no title or artwork"
                    else "MediaSession artwork URI inaccessible; no title")
                return@execute
            }
            if (RecentPreviewStore.enabled(ctx)) {
                recentSignatures[pkg] = signature to time
                val saved = RecentMediaStore.remember(ctx, pkg, title, art, source)
                if (!saved) RecentMediaStore.note(ctx, pkg,
                    "Media metadata received but could not cache: $reason")
            }
            // The source bitmap may come from another app's MediaMetadata. Do not recycle it.
            if (art != null && art !== bitmap) art.recycle()
        }
    }

    fun stop() {
        val m = manager
        val listener = sessionListener
        if (m != null && listener != null) runCatching {
            m.removeOnActiveSessionsChangedListener(listener)
        }
        registered.values.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        registered.clear()
        manager = null
        sessionListener = null
        context = null
        foreground = null
        probeTimes.clear()
        recentSignatures.clear()
        lastRefresh = 0L
        lastAttempt = 0L
    }
}
