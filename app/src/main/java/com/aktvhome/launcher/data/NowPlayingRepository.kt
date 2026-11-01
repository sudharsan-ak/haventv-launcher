package com.aktvhome.launcher.data

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aktvhome.launcher.HavenMediaListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PlaybackKind { PLAYING, BUFFERING, PAUSED }

/** A live Android media session, not a permanent entry in Continue Watching. */
data class PlayingMedia(
    val id: String,
    val pkg: String,
    val title: String,
    val subtitle: String?,
    val kind: PlaybackKind,
    val lastUpdated: Long,
    val artwork: ImageBitmap?,
    val returnToSession: PendingIntent?,
)

/** Keep playing sessions first; paused sessions are eligible only while the app retains them. */
fun sortMediaSessions(items: List<PlayingMedia>): List<PlayingMedia> =
    items.sortedWith(compareBy<PlayingMedia> { it.kind == PlaybackKind.PAUSED }
        .thenBy { it.kind == PlaybackKind.BUFFERING }
        .thenByDescending { it.lastUpdated })

/** Shares the same Notification Listener access used by Haven's NBA alert suppression. */
class NowPlayingRepository(private val context: Context) {
    var sessions by mutableStateOf<List<PlayingMedia>>(emptyList())
        private set

    fun clear() { sessions = emptyList() }

    suspend fun refresh() {
        if (!NbaAlerts.canSeePlaying(context)) {
            clear()
            return
        }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val manager = context.getSystemService(MediaSessionManager::class.java)
                val component = ComponentName(context, HavenMediaListener::class.java)
                val now = SystemClock.elapsedRealtime()
                manager.getActiveSessions(component).mapNotNull { controller ->
                    val playback = controller.playbackState ?: return@mapNotNull null
                    val kind = when (playback.state) {
                        PlaybackState.STATE_PLAYING -> PlaybackKind.PLAYING
                        PlaybackState.STATE_BUFFERING -> PlaybackKind.BUFFERING
                        PlaybackState.STATE_PAUSED -> PlaybackKind.PAUSED
                        else -> return@mapNotNull null
                    }
                    // Ignore an abandoned paused session instead of showing yesterday's video.
                    val updated = playback.lastPositionUpdateTime
                    if (kind == PlaybackKind.PAUSED && updated > 0 &&
                        now - updated > MAX_PAUSED_AGE_MS) return@mapNotNull null
                    val metadata = controller.metadata
                    val description = metadata?.description
                    val title = description?.title?.toString()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val art = runCatching {
                        val image = description.iconBitmap
                            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        image?.asImageBitmap()
                    }.getOrNull()
                    PlayingMedia(
                        id = "${controller.packageName}:${controller.sessionToken}",
                        pkg = controller.packageName,
                        title = title,
                        subtitle = description.subtitle?.toString(),
                        kind = kind,
                        lastUpdated = updated,
                        artwork = art,
                        returnToSession = controller.sessionActivity,
                    )
                }
            }.getOrDefault(emptyList())
        }
        sessions = sortMediaSessions(found).take(MAX_SESSIONS)
    }

    /** Prefer the app-provided media return action, then its normal TV launch intent. */
    fun open(session: PlayingMedia): Boolean {
        val pending = session.returnToSession
        if (pending != null && runCatching { pending.send() }.isSuccess) return true
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(session.pkg)
            ?: pm.getLaunchIntentForPackage(session.pkg) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val MAX_SESSIONS = 8
        private const val MAX_PAUSED_AGE_MS = 2 * 60 * 60 * 1000L
    }
}
