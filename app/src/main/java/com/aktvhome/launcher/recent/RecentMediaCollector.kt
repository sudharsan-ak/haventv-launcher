package com.aktvhome.launcher.recent

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.aktvhome.launcher.HavenMediaListener
import com.aktvhome.launcher.data.NbaAlerts
import java.util.concurrent.Executors

/**
 * Opt-in foreground MediaSession listener. Notification events + a slow polling fallback
 * help on TVs that drop active-session callbacks. No periodic screen capture occurs.
 */
internal object RecentMediaCollector {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { action ->
        Thread(action, "HavenRecentMedia").apply { isDaemon = true }
    }
    private var context: Context? = null
    private var manager: MediaSessionManager? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val registered = mutableMapOf<MediaSession.Token,
        Pair<MediaController, MediaController.Callback>>()
    private var foreground: String? = null
    private var lastRefresh = 0L
    private var lastAttempt = 0L
    private val probeTimes = mutableMapOf<String, Long>()
    // Written by the media IO worker and read/reset from the service thread.
    private val historyTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val signatures =
        java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private var pollGeneration = 0
    private var polling = false

    fun currentForeground(): String? = foreground

    fun onForeground(service: AccessibilityService, pkg: String) {
        if (!RecentPreviewStore.enabled(service)) {
            stop()
            return
        }
        val switched = foreground != pkg
        foreground = pkg
        ensureStarted(service.applicationContext)
        if (manager != null && (switched ||
            SystemClock.elapsedRealtime() - lastRefresh >= 6_000L)) refreshControllers()
        if (switched) sampleCurrent(pkg, force = true)
        schedulePolling()
    }

    fun beforeHome(service: AccessibilityService, pkg: String) {
        if (RecentPreviewStore.enabled(service) && foreground == pkg)
            sampleCurrent(pkg, force = true)
    }

    /** Called by the existing NotificationListenerService, without reading notifications. */
    fun onNotificationSignal(ctx: Context, eventPackage: String?) {
        main.post {
            val pkg = foreground ?: return@post
            if (!RecentPreviewStore.enabled(ctx) ||
                (eventPackage != null && eventPackage != pkg)) return@post
            if (manager == null) ensureStarted(ctx.applicationContext)
            if (SystemClock.elapsedRealtime() - lastRefresh > 2_000L) refreshControllers()
            sampleCurrent(pkg, force = false)
        }
    }

    fun leaveForeground() {
        foreground = null
        pollGeneration++
        polling = false
    }

    private fun schedulePolling() {
        if (foreground == null || polling) return
        polling = true
        val generation = ++pollGeneration
        main.postDelayed(object : Runnable {
            override fun run() {
                if (generation != pollGeneration) return
                val ctx = context ?: run { polling = false; return }
                val pkg = foreground
                if (pkg == null || !RecentPreviewStore.enabled(ctx)) {
                    polling = false
                    return
                }
                val interactive = (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isInteractive != false
                if (interactive) {
                    if (manager == null) ensureStarted(ctx)
                    refreshControllers()
                }
                main.postDelayed(this, 8_000L)
            }
        }, 8_000L)
    }

    private fun ensureStarted(ctx: Context): Boolean {
        if (manager != null) return true
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
                RecentMediaStore.note(ctx, it,
                    "MediaSession listener unavailable: " + e.javaClass.simpleName)
            }
            false
        }
    }

    private fun refreshControllers() {
        val m = manager ?: return
        val ctx = context ?: return
        if (SystemClock.elapsedRealtime() - lastRefresh < 1_500L) return
        lastRefresh = SystemClock.elapsedRealtime()
        io.execute {
            val response = runCatching {
                m.getActiveSessions(ComponentName(ctx, HavenMediaListener::class.java))
            }
            main.post {
                if (manager !== m) return@post
                response.onSuccess { bindControllers(it) }
                    .onFailure { error ->
                        foreground?.let { RecentMediaStore.note(ctx, it,
                            "MediaSession query failed: " + error.javaClass.simpleName) }
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
                    if (controller.packageName == foreground) schedule(controller, "Metadata", true)
                }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (controller.packageName == foreground) schedule(controller, "Playback", true)
                }
                override fun onSessionDestroyed() {
                    registered.remove(token)
                    foreground?.let { if (it == controller.packageName) refreshControllers() }
                }
            }
            runCatching {
                controller.registerCallback(cb, main)
                registered[token] = controller to cb
            }
        }
        foreground?.let { sampleCurrent(it, force = false) }
    }

    private fun sampleCurrent(pkg: String, force: Boolean) {
        val matching = registered.values.map { it.first }.filter { it.packageName == pkg }
        if (matching.isEmpty()) {
            context?.let { RecentMediaStore.note(it, pkg,
                "No active MediaSession for this foreground app") }
            return
        }
        val active = matching.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: matching.first()
        schedule(active, "Session sample", force)
    }

    private fun schedule(controller: MediaController, reason: String, force: Boolean = false) {
        val ctx = context ?: return
        val pkg = controller.packageName
        if (pkg != foreground || !RecentPreviewStore.enabled(ctx)) return
        val now = SystemClock.elapsedRealtime()
        val last = probeTimes[pkg] ?: 0L
        if (now - last < (if (force) 900L else 8_000L)) return
        probeTimes[pkg] = now
        io.execute {
            if (!RecentPreviewStore.enabled(ctx)) return@execute
            // The controller is checked again because a queued callback may run after Home.
            val metadata = runCatching { controller.metadata }.getOrNull()
            // Some players only publish a queue title during session teardown.
            val queueTitle = runCatching { controller.queueTitle?.toString() }.getOrNull()
            if (metadata == null && queueTitle.isNullOrBlank()) {
                RecentMediaStore.note(ctx, pkg, "MediaSession active, no metadata/queue title")
                return@execute
            }
            val title = listOfNotNull(
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                metadata?.description?.title?.toString(),
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                queueTitle
            ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val subtitle = listOfNotNull(
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            ).firstOrNull { it.isNotBlank() && it.trim() != title }?.trim().orEmpty()
            val state = runCatching { controller.playbackState }.getOrNull()
            val playbackState = state?.state ?: PlaybackState.STATE_NONE
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            var position = state?.position ?: -1L
            if (state != null && position >= 0 && playbackState == PlaybackState.STATE_PLAYING &&
                state.lastPositionUpdateTime > 0) {
                val delta = ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
                    .coerceAtLeast(0L) * state.playbackSpeed).toLong()
                position = (position + delta).coerceAtLeast(0L)
            }
            if (duration > 0 && position > duration) position = duration
            val raw = runCatching {
                metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    ?: metadata?.description?.iconBitmap
            }.getOrNull()
            val artUris = listOfNotNull(
                metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI),
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                metadata?.description?.iconUri?.toString(),
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ).filter { it.isNotBlank() }.distinct()
            val signature = title + "|" + subtitle + "|" + artUris.joinToString("|") +
                "|" + (raw?.generationId?.toString().orEmpty())
            val lastSignature = signatures[pkg]
            val changed = lastSignature?.first != signature ||
                System.currentTimeMillis() - (lastSignature?.second ?: 0L) >= 60_000L
            val needsHistory = SystemClock.elapsedRealtime() -
                (historyTimes[pkg] ?: 0L) >= 12_000L || changed
            if (!changed && !needsHistory) return@execute
            var art: android.graphics.Bitmap? = null
            var artSource = "title only"
            if (changed) {
                art = RecentArtworkLoader.scale(raw)
                if (art != null) artSource = "MediaSession bitmap"
                else for (uri in artUris) {
                    art = RecentArtworkLoader.load(ctx, uri)
                    if (art != null) {
                        artSource = "MediaSession artwork URI"
                        break
                    }
                }
                if (art == null && artUris.isNotEmpty()) artSource = "Artwork URI unreadable"
            }
            if (RecentPreviewStore.enabled(ctx) && (title.isNotBlank() || art != null)) {
                if (changed) {
                    RecentMediaStore.remember(ctx, pkg, title, art, artSource)
                    signatures[pkg] = signature to System.currentTimeMillis()
                }
                if (needsHistory && title.isNotBlank()) {
                    RecentPlaybackHistory.record(ctx, pkg, title, subtitle, position,
                        duration, playbackState, art)
                    main.post { historyTimes[pkg] = SystemClock.elapsedRealtime() }
                }
            } else RecentMediaStore.note(ctx, pkg,
                "Session found but no title/artwork: $reason")
            if (art != null && art !== raw) art.recycle()
        }
    }

    fun stop() {
        pollGeneration++
        polling = false
        val m = manager
        val listener = sessionListener
        if (m != null && listener != null) runCatching {
            m.removeOnActiveSessionsChangedListener(listener)
        }
        registered.values.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        registered.clear()
        context = null
        manager = null
        sessionListener = null
        foreground = null
        probeTimes.clear()
        historyTimes.clear()
        signatures.clear()
        lastRefresh = 0L
        lastAttempt = 0L
    }
}
