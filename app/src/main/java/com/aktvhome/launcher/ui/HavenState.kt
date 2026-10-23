package com.aktvhome.launcher.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aktvhome.launcher.data.AppCollection
import com.aktvhome.launcher.data.AppEntry
import com.aktvhome.launcher.data.AppRepository
import com.aktvhome.launcher.data.Defaults
import com.aktvhome.launcher.data.DnsChoice
import com.aktvhome.launcher.data.PrivateDns
import com.aktvhome.launcher.data.InputRepository
import com.aktvhome.launcher.data.LayoutStore
import com.aktvhome.launcher.data.SettingsStore
import com.aktvhome.launcher.data.TvInput
import com.aktvhome.launcher.data.Wallpapers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalTime

/** What floats over the home screen. The home itself always stays drawn behind. */
enum class Overlay { NONE, QUICK, PERSONALIZE }

/** A collection as shown on screen: only installed, non-hidden apps, in the user's order. */
data class ShownCollection(val collection: AppCollection, val apps: List<AppEntry>) {
    val isInputs get() = collection.isInputs
}

/** App-wide UI state shared by every theme. */
class HavenState(private val context: Context, private val scope: CoroutineScope) {

    private val appRepo = AppRepository(context)
    private val inputRepo = InputRepository(context)
    val layoutStore = LayoutStore(context)
    val settingsStore = SettingsStore(context)
    val wallpapers = Wallpapers(context, scope, settingsStore)

    var apps by mutableStateOf<Map<String, AppEntry>>(emptyMap())
        private set
    var inputs by mutableStateOf<List<TvInput>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    var overlay by mutableStateOf(Overlay.NONE)
    /** Bumped each time the home screen comes back into view; themes key their welcome on it. */
    var arrivals by mutableStateOf(0)
    /** Bumped when Home is pressed while Haven is showing; themes move focus back to the first row. */
    var homePresses by mutableStateOf(0)
    /** True when Android opened Haven as the home screen (Back then has nowhere to go). */
    var isHome by mutableStateOf(false)
    var selectedCollection by mutableStateOf<String?>(null)
    var focusedApp by mutableStateOf<AppEntry?>(null)
    var menuFor by mutableStateOf<AppEntry?>(null)
    /** Package currently being moved with left/right, if any. */
    var moving by mutableStateOf<String?>(null)
    var now by mutableStateOf(LocalTime.now())

    fun refresh() {
        scope.launch {
            val list = appRepo.loadApps()
            apps = list.associateBy { it.pkg }
            layoutStore.reconcile(list.map { it.pkg })
            inputs = inputRepo.loadInputs()
            loaded = true
        }
    }

    fun shownCollections(): List<ShownCollection> {
        val layout = layoutStore.layout
        return layout.collections
            .filter { !it.hidden }
            .map { c -> ShownCollection(c, c.apps.filter { it !in layout.hidden }.mapNotNull { apps[it] }) }
            // An empty Other is noise; empty collections the user made stay so they can fill them.
            .filter { it.apps.isNotEmpty() || it.collection.id != Defaults.OTHER_ID }
            .filter { !it.isInputs || inputs.isNotEmpty() }
    }

    /**
     * Picks a Private DNS and applies it to the TV. Returns false (and says why) when Haven
     * doesn't have the one-time ADB permission yet.
     */
    fun selectDns(choice: DnsChoice, custom: String = settingsStore.settings.dnsCustom): Boolean {
        settingsStore.update {
            it.copy(
                dnsChoice = choice,
                dnsCustom = custom,
                dnsProvider = if (choice.host != null || choice == DnsChoice.CUSTOM) choice else it.dnsProvider,
            )
        }
        val ok = PrivateDns.apply(context, choice, custom)
        if (!ok) toast("Haven needs a one-time ADB permission to change Private DNS")
        return ok
    }

    /** Quick settings switch: on = the last filtering provider (AdGuard by default), off = Auto. */
    fun toggleDns() {
        val s = settingsStore.settings
        if (PrivateDns.current(context).first == "hostname") selectDns(DnsChoice.AUTO) else selectDns(s.dnsProvider)
    }

    fun dnsStatus() = PrivateDns.status(context)
    fun dnsMode() = PrivateDns.current(context)
    fun canSetDns() = PrivateDns.canWrite(context)

    fun dayness(): Float = com.aktvhome.launcher.ui.theme.dayness(settingsStore.settings.dayMode, now)

    /** "Good evening, Sam" (or just "Good evening" with no name), or null when the greeting is off. */
    fun greetingLine(): String? {
        val s = settingsStore.settings
        if (!s.showGreeting) return null
        val base = com.aktvhome.launcher.ui.theme.greeting(now)
        return if (s.name.isBlank()) base else "$base, ${s.name.trim()}"
    }

    fun currentCollection(shown: List<ShownCollection>): ShownCollection? =
        shown.firstOrNull { it.collection.id == selectedCollection } ?: shown.firstOrNull()

    fun hiddenApps(): List<AppEntry> =
        layoutStore.layout.hidden.mapNotNull { apps[it] }.sortedBy { it.label.lowercase() }

    fun launch(app: AppEntry) {
        val intent = appRepo.launchIntent(app.pkg)
        if (intent == null) toast("Can't open ${app.label}") else runCatching { context.startActivity(intent) }
            .onFailure { toast("Can't open ${app.label}") }
    }

    fun switchTo(input: TvInput) {
        runCatching { context.startActivity(inputRepo.switchIntent(input)) }
            .onFailure { toast("Can't switch to ${input.label}") }
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
