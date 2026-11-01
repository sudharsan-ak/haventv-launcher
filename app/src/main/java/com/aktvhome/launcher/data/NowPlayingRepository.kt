package com.aktvhome.launcher.data

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.util.Log
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

/** Choose a safe label when an otherwise-valid media session does not publish a title. */
fun mediaSessionTitle(vararg candidates: String?): String =
    candidates.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Resume playback"

/** Keep playing sessions first; paused sessions are eligible only while the app retains them. */
fun sortMediaSessions(items: List<PlayingMedia>): List<PlayingMedia> =
    items.sortedWith(compareBy<PlayingMedia> { it.kind == PlaybackKind.PAUSED }
        .thenBy { it.kind == PlaybackKind.BUFFERING }
        .thenByDescending { it.lastUpdated })

/** Shares the same Notification Listener access used by Haven's NBA alert suppression. */
class NowPlayingRepository(private val context: Context) {
    var sessions by mutableStateOf<List<PlayingMedia>>(emptyList())
        private set

    /** Visible in Watch Now settings so a missing card is diagnosable on the TV. */
    var detectionStatus by mutableStateOf("Checking Android media sessions…")
        private set

    fun clear() {
        sessions = emptyList()
        detectionStatus = "Now Playing is switched off."
    }

    suspend fun refresh() {
        if (!NbaAlerts.canSeePlaying(context)) {
            sessions = emptyList()
            detectionStatus = "Notification access is not enabled for Haven."
            return
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val manager = context.getSystemService(MediaSessionManager::class.java)
                val component = ComponentName(context, HavenMediaListener::class.java)
                val now = SystemClock.elapsedRealtime()
                val controllers = manager.getActiveSessions(component)
                val excluded = mutableListOf<String>()
                val found = controllers.mapNotNull { controller ->
                    // A misbehaving third-party session must not hide every other player's session.
                    runCatching {
                        val playback = controller.playbackState
                        val kind = when (playback?.state) {
                            PlaybackState.STATE_PLAYING -> PlaybackKind.PLAYING
                            PlaybackState.STATE_BUFFERING -> PlaybackKind.BUFFERING
                            PlaybackState.STATE_PAUSED -> PlaybackKind.PAUSED
                            else -> null
                        }
                        if (kind == null) {
                            excluded.add("${controller.packageName}: not playing/paused (state ${playback?.state ?: "unknown"})")
                            null
                        } else {
                            val updated = playback?.lastPositionUpdateTime ?: 0L
                            if (kind == PlaybackKind.PAUSED && updated > 0 &&
                                now - updated > MAX_PAUSED_AGE_MS) {
                                excluded.add("${controller.packageName}: stale paused session")
                                null
                            } else {
                                val metadata = controller.metadata
                                val description = metadata?.description
                                val title = mediaSessionTitle(
                                    description?.title?.toString(),
                                    metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                                    metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                                )
                                val art = runCatching {
                                    val image = description?.iconBitmap
                                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                                    image?.asImageBitmap()
                                }.getOrNull()
                                PlayingMedia(
                                    id = "${controller.packageName}:${controller.sessionToken}",
                                    pkg = controller.packageName,
                                    title = title,
                                    subtitle = description?.subtitle?.toString(),
                                    kind = kind,
                                    lastUpdated = updated,
                                    artwork = art,
                                    returnToSession = controller.sessionActivity,
                                )
                            }
                        }
                    }.getOrElse { error ->
                        excluded.add("${controller.packageName}: ${error.javaClass.simpleName}")
                        null
                    }
                }
                val status = when {
                    controllers.isEmpty() ->
                        "Android reports 0 media sessions. Check SmartTube's session with ADB."
                    found.isNotEmpty() ->
                        "${found.size} usable of ${controllers.size} Android media sessions: " +
                            found.joinToString { it.pkg.substringAfterLast('.') }
                    else ->
                        "${controllers.size} Android media sessions, none eligible: " +
                            excluded.take(2).joinToString("; ")
                }
                found to status
            }.getOrElse { error ->
                emptyList<PlayingMedia>() to
                    "Cannot read Android media sessions: ${error.javaClass.simpleName}"
            }
        }
        sessions = sortMediaSessions(result.first).take(MAX_SESSIONS)
        if (detectionStatus != result.second) {
            detectionStatus = result.second
            Log.i("HavenNowPlaying", detectionStatus)
        }
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
