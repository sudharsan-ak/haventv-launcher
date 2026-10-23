package com.aktvhome.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import com.aktvhome.launcher.data.Background
import com.aktvhome.launcher.data.HomeOverride
import com.aktvhome.launcher.data.PrivateDns
import kotlinx.coroutines.launch
import com.aktvhome.launcher.data.SettingsStore
import com.aktvhome.launcher.data.ThemeId
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.kingdom.KingdomHome
import com.aktvhome.launcher.ui.settings.Personalize
import com.aktvhome.launcher.ui.settings.QuickSettings
import com.aktvhome.launcher.ui.settings.RestorePrompt
import com.aktvhome.launcher.ui.watch.ContinuePanel
import com.aktvhome.launcher.ui.watch.GameDetail
import com.aktvhome.launcher.ui.watch.LivePanel
import com.aktvhome.launcher.ui.watch.NbaPanel
import com.aktvhome.launcher.ui.watch.TeamSchedule
import com.aktvhome.launcher.ui.watch.WatchDrawer
import com.aktvhome.launcher.ui.theme.Palettes
import com.aktvhome.launcher.ui.theme.dayness
import com.aktvhome.launcher.ui.tide.TideHome
import kotlinx.coroutines.delay
import java.time.LocalTime

class MainActivity : ComponentActivity() {

    private lateinit var state: HavenState

    /** Reloads the app list whenever something is installed, removed or updated. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = state.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = HavenState(applicationContext, lifecycleScope)
        state.isHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true

        setContent {
            val s = state.settingsStore.settings
            // Over a photo the layout always uses its night look: light text on a darkened picture.
            val onPhoto = s.backgroundFor(s.theme) == Background.PHOTO && state.wallpapers.current != null
            val colors = Palettes.colors(s.theme, if (onPhoto) 0f else dayness(s.dayMode, state.now))

            LaunchedEffect(Unit) {
                while (true) {
                    state.now = LocalTime.now()
                    state.wallpapers.tick()
                    state.watchNow.tick()
                    state.backup.tick()
                    state.sony.refreshIfDue()
                    delay(15_000)
                }
            }

            // As the home screen there's nothing to go back to, so Back only ends a move.
            BackHandler(enabled = state.moving != null || state.isHome) { state.moving = null }

            Crossfade(targetState = s.theme, animationSpec = tween(350), label = "theme") { theme ->
                when (theme) {
                    ThemeId.TIDE -> TideHome(state, colors)
                    ThemeId.KINGDOM -> KingdomHome(state, colors)
                }
            }

            // Settings float over the home, which stays live behind them.
            when (state.overlay) {
                Overlay.QUICK -> QuickSettings(state, colors)
                Overlay.PERSONALIZE -> Personalize(state, colors)
                Overlay.WATCH -> WatchDrawer(state, colors)
                Overlay.NBA -> NbaPanel(state, colors)
                Overlay.LIVE -> LivePanel(state, colors)
                Overlay.CONTINUE -> ContinuePanel(state, colors)
                Overlay.NONE -> Unit
            }
            // Schedule and game detail stack on top of the drawer (or the home, from the strip).
            if (state.scheduleOpen) TeamSchedule(state, colors)
            state.detailGame?.let { GameDetail(state, colors, it) }
            if (state.backup.offerRestore) RestorePrompt(state, colors)
        }
    }

    /** Home pressed while Haven is already open: close whatever is up and go back to the first row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return
        state.isHome = true
        state.overlay = Overlay.NONE
        state.detailGame = null
        state.scheduleOpen = false
        state.menuFor = null
        state.moving = null
        state.focusedApp = null
        state.homePresses++
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        state.now = LocalTime.now()
        state.arrivals++
        state.refresh()
        // "Keep enforced": put the chosen Private DNS back if something changed it.
        PrivateDns.enforce(this, SettingsStore.readDns(this))
        // Give the Home service a few seconds to connect (e.g. at boot) before treating it as stale.
        lifecycleScope.launch {
            delay(5_000)
            HomeOverride.reconnectIfStale(applicationContext)
        }    }

    override fun onStop() {
        unregisterReceiver(packageReceiver)
        super.onStop()
    }
}
