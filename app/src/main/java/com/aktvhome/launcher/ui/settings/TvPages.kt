@file:OptIn(ExperimentalLayoutApi::class) // FlowRow for the chips

package com.aktvhome.launcher.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aktvhome.launcher.HomeOverrideService
import com.aktvhome.launcher.data.Capability
import com.aktvhome.launcher.data.GenericControl
import com.aktvhome.launcher.data.SleepTimer
import com.aktvhome.launcher.data.SonyControl
import com.aktvhome.launcher.data.SonySetting
import com.aktvhome.launcher.data.SonyVolume
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.havenSetting
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The TV pages. Quick settings opens one at a time (Wi-Fi and Bluetooth share a page there, as
 * tabs); All settings › TV controls shows them as sections.
 */
enum class TvPage(val title: String) {
    PICTURE("Picture"), SOUND("Sound"), SAVING("Power saving"), SLEEP("Sleep timer"),
    WIFI("Wi-Fi"), BLUETOOTH("Bluetooth"), NETWORK("Wi-Fi & Bluetooth"),
}

/**
 * What the TV's control service reports for picture, sound and power saving, loaded when a TV
 * page opens and kept in step with each change.
 */
class TvSettings(private val sony: SonyControl) {
    var picture by mutableStateOf<List<SonySetting>?>(null)
    var output by mutableStateOf<String?>(null)
    var volume by mutableStateOf<SonyVolume?>(null)
    var subwoofer by mutableStateOf<Int?>(null)
    var saving by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    /** Last change the TV refused, shown on the page. */
    var problem by mutableStateOf<String?>(null)

    val paired: Boolean get() = sony.paired

    suspend fun load() {
        if (!sony.paired) { loaded = true; return }
        picture = sony.pictureSettings()
        output = sony.soundOutput()
        volume = sony.volume()
        subwoofer = sony.subwooferLevel()
        saving = sony.powerSaving()
        loaded = true
    }

    fun value(target: String): String? = picture?.firstOrNull { it.target == target }?.current

    suspend fun setPicture(target: String, value: String) {
        picture = picture?.map { if (it.target == target) it.copy(current = value) else it }
        problem = if (sony.setPicture(target, value)) null else "The TV didn't accept ${label(target)}."
        // A new picture mode brings its own brightness, contrast and so on.
        if (target == "pictureMode") sony.pictureSettings()?.let { picture = it }
    }

    suspend fun setOutput(value: String) {
        problem = if (sony.setSoundOutput(value)) null else "The TV didn't switch to ${outputLabel(value)}."
        output = sony.soundOutput() ?: output
        volume = sony.volume() ?: volume
    }

    suspend fun setVolume(level: Int) {
        val v = volume ?: return
        volume = v.copy(volume = level)
        problem = if (sony.setVolume(v.target, level)) null else "The TV didn't change the volume."
    }

    suspend fun toggleMute() {
        val v = volume ?: return
        volume = v.copy(mute = !v.mute)
        problem = if (sony.setMute(!v.mute)) null else "The TV didn't change mute."
    }

    suspend fun setSubwoofer(level: Int) {
        subwoofer = level
        problem = if (sony.setSubwooferLevel(level)) null else "The TV didn't change the subwoofer level."
        subwoofer = sony.subwooferLevel() ?: subwoofer
    }

    suspend fun setSaving(mode: String) {
        saving = mode
        problem = if (sony.setPowerSaving(mode)) null else "The TV didn't change power saving."
        saving = sony.powerSaving() ?: saving
    }

    /** Tile line for Picture, e.g. "Standard · Brightness 40". */
    fun pictureSummary(): String? {
        val mode = value("pictureMode") ?: return null
        return listOfNotNull(pretty(mode), value("brightness")?.let { "Brightness $it" }).joinToString(" · ")
    }

    fun soundSummary(): String? {
        val o = output ?: return null
        val v = volume
        return outputLabel(o) + (if (v == null) "" else if (v.mute) " · Muted" else " · Volume ${v.volume}")
    }
}

// ---- Names as the TV's menus show them ----

private val SLIDER_ORDER = listOf("brightness", "contrast", "color", "sharpness", "hue")
private val CHOICE_ORDER = listOf("pictureMode", "colorTemperature", "hdrMode", "lightSensor", "autoLocalDimming", "colorSpace")

private val LABELS = mapOf(
    "pictureMode" to "Picture mode", "colorTemperature" to "Color temperature", "hdrMode" to "HDR mode",
    "lightSensor" to "Light sensor", "autoLocalDimming" to "Local dimming", "colorSpace" to "Color space",
)

private val VALUES = mapOf(
    "sRgb/bt709" to "sRGB/BT.709", "dci" to "DCI", "adobeRgb" to "Adobe RGB", "bt2020" to "BT.2020",
    "hdr10" to "HDR10", "hlg" to "HLG", "expert1" to "Expert 1", "expert2" to "Expert 2", "pictureOff" to "Picture off",
)

fun label(target: String): String = LABELS[target] ?: words(target)

fun pretty(value: String): String = VALUES[value] ?: words(value)

/** "tableTop" → "Table top". */
private fun words(s: String): String =
    s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

private val OUTPUTS = listOf("speaker" to "TV speakers", "hdmi" to "Audio system", "speaker_hdmi" to "Both")

fun outputLabel(value: String): String = OUTPUTS.firstOrNull { it.first == value }?.second ?: pretty(value)

private val SAVING = listOf("off" to "Off", "low" to "Low", "high" to "High", "pictureOff" to "Picture off")

fun savingLabel(value: String): String = SAVING.firstOrNull { it.first == value }?.second ?: pretty(value)

// ---- Quick settings: one page at a time ----

/**
 * The body of [page] in Quick settings. [first] goes on its first control; focusing that control
 * scrolls the page back to the top, so nothing above it stays cut off.
 */
@Composable
fun TvPageBody(page: TvPage, state: HavenState, tv: TvSettings, colors: ThemeColors, first: FocusRequester) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val top = Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) scope.launch { scroll.animateScrollTo(0) } }
    SettingsPickerHost(colors, Modifier.fillMaxWidth().height(390.dp)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tv.problem?.let { Text(it, color = colors.ink, fontSize = 11.sp) }
            TvSection(page, state, tv, colors, top)
        }
    }
}

@Composable
private fun TvSection(page: TvPage, state: HavenState, tv: TvSettings, colors: ThemeColors, top: Modifier) {
    when (page) {
        TvPage.PICTURE -> PicturePage(tv, colors, top)
        // On a Sony, the TV's own sound settings (they say how to pair if needed); elsewhere this
        // device's volume.
        TvPage.SOUND -> if (state.device.isSony) SoundPage(tv, colors, top) else GenericSoundPage(colors, top)
        TvPage.SAVING -> SavingPage(tv, colors, top)
        TvPage.SLEEP -> SleepPage(colors, top)
        TvPage.WIFI -> WifiPage(state, colors, top)
        TvPage.BLUETOOTH -> BluetoothPage(colors, top)
        TvPage.NETWORK -> NetworkPage(state, colors, top)
    }
}

// ---- All settings › TV & network › TV controls ----

/** Sections of TV controls; Screen and Accessibility open the TV's own pages. */
private enum class TvSectionChip(val label: String, val page: TvPage?) {
    PICTURE("Picture", TvPage.PICTURE), SOUND("Sound", TvPage.SOUND), SAVING("Power saving", TvPage.SAVING),
    SLEEP("Sleep timer", TvPage.SLEEP), WIFI("Wi-Fi & network", TvPage.WIFI), BLUETOOTH("Bluetooth", TvPage.BLUETOOTH),
    SCREEN("Screen ›", null), ACCESSIBILITY("Accessibility ›", null),
}

/**
 * The TV controls tab: section chips across the top, the chosen section below. Sections this TV
 * can't do are left out: Picture and Power saving off a Sony, Screen without display settings,
 * Sleep timer when the device can't be put to sleep.
 */
@Composable
fun TvControlsPage(state: HavenState, colors: ThemeColors, first: Modifier) {
    val context = LocalContext.current
    val tv = remember { TvSettings(state.sony) }
    val device = state.device
    val chips = TvSectionChip.entries.filter { c ->
        when (c) {
            TvSectionChip.PICTURE, TvSectionChip.SAVING -> device.isSony
            TvSectionChip.SCREEN -> device.supported(Capability.DISPLAY_SETTINGS)
            TvSectionChip.SLEEP -> device.supported(Capability.SLEEP)
            else -> true
        }
    }
    val requestedSubsection = LocalSettingSearchTarget.current?.settingId?.let(::havenSetting)?.subsection
    val initialSection = chips.firstOrNull { it.name == requestedSubsection } ?: chips.first()
    var section by remember(requestedSubsection) { mutableStateOf(initialSection) }
    LaunchedEffect(Unit) { tv.load() }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEachIndexed { i, c ->
            Chip(c.label, c == section, colors, if (i == 0) first else Modifier) {
                when (c) {
                    TvSectionChip.SCREEN -> openSystemPage(context, screenPage(context, device.isSony))
                    TvSectionChip.ACCESSIBILITY -> openSystemPage(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    else -> { tv.problem = null; section = c }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.edge))
    tv.problem?.let { Text(it, color = colors.ink, fontSize = 11.sp) }
    section.page?.let { TvSection(it, state, tv, colors, Modifier) }
}

// ---- Sections ----

@Composable
private fun NotPaired(tv: TvSettings, colors: ThemeColors): Boolean {
    if (!tv.loaded) {
        Text("Asking the TV…", color = colors.inkSoft, fontSize = 12.sp)
        return true
    }
    if (tv.paired) return false
    Text(
        "Haven isn't paired with the TV yet. Turn on Settings › Remote device settings › Control remotely, then press the power button at the bottom right of Quick settings to pair (once).",
        color = colors.inkSoft, fontSize = 12.sp,
    )
    return true
}

@Composable
private fun PicturePage(tv: TvSettings, colors: ThemeColors, top: Modifier) {
    if (NotPaired(tv, colors)) return
    val scope = rememberCoroutineScope()
    val list = tv.picture
    if (list == null) {
        Text("The TV didn't send its picture settings. Try again in a moment.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    fun rank(order: List<String>, t: String) = order.indexOf(t).let { if (it < 0) 99 else it }
    val sliders = list.filter { it.isRange }.sortedBy { rank(SLIDER_ORDER, it.target) }
    val choices = list.filter { !it.isRange && it.choices.isNotEmpty() }.sortedBy { rank(CHOICE_ORDER, it.target) }
    sliders.forEachIndexed { i, s ->
        SliderRow(label(s.target), s.current.toIntOrNull() ?: 0, s.min!!, s.max!!, s.step, colors, if (i == 0) top else Modifier) { v ->
            scope.launch { tv.setPicture(s.target, v.toString()) }
        }
    }
    choices.forEachIndexed { i, s ->
        ChipRow(label(s.target), s.choices.map { it to pretty(it) }, s.current, colors, if (sliders.isEmpty() && i == 0) top else Modifier) { v ->
            scope.launch { tv.setPicture(s.target, v) }
        }
    }
}

@Composable
private fun SoundPage(tv: TvSettings, colors: ThemeColors, top: Modifier) {
    if (NotPaired(tv, colors)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bt = rememberBluetooth(AUDIO_PROFILES)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bt.allowed = it }
    // Set while the TV's page for a speaker is open; the output to switch to once it closes.
    val away = remember { booleanArrayOf(false) }
    var nextOutput by remember { mutableStateOf<String?>(null) }
    val speakers = bt.paired.filter { it.audio }
    val speaker = speakers.firstOrNull { it.address in bt.connected }

    // Back from the speaker's page: switch the output if one was picked, then read everything
    // again. A speaker takes a few seconds to connect, so look once more a little later.
    OnResume {
        if (!away[0]) return@OnResume
        away[0] = false
        nextOutput?.let { nextOutput = null; tv.setOutput(it) }
        repeat(2) { i ->
            if (i > 0) delay(3_000)
            bt.refresh(AUDIO_PROFILES)
            tv.load()
        }
    }

    fun device(d: BtDevice, action: String) {
        away[0] = true
        pressOnDevicePage(context, d, action)
    }

    val v = tv.volume
    if (v != null) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (v.mute) "Muted" else v.volume.toString(), color = colors.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Volume · ${tv.output?.let(::outputLabel) ?: ""}", color = colors.inkSoft, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        SliderRow("Volume", v.volume, v.min, v.max, 1, colors, top) { n -> scope.launch { tv.setVolume(n) } }
    }
    // Mute and where the sound goes, as one row of chips.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (v != null) Chip("Mute", v.mute, colors) { scope.launch { tv.toggleMute() } }
        tv.output?.let { current ->
            OUTPUTS.forEachIndexed { i, (value, text) ->
                Chip(text, speaker == null && value == current, colors, if (v == null && i == 0) top else Modifier) {
                    // A connected speaker keeps the sound until it's disconnected.
                    if (speaker != null) {
                        nextOutput = value
                        device(speaker, "Disconnect")
                    } else {
                        scope.launch { tv.setOutput(value) }
                    }
                }
            }
        }
        // Paired Bluetooth speakers and headphones: one press connects through the TV's own page.
        if (!bt.allowed) {
            Chip("Show paired devices", false, colors) { ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        } else {
            speakers.forEach { d ->
                Chip(d.name, d == speaker, colors) {
                    if (d == speaker) openSystemPage(context, devicePage(d)) else device(d, "Connect")
                }
            }
        }
    }
    // Sony's menu allows 0–24 for the subwoofer.
    tv.subwoofer?.let { level ->
        SliderRow("Subwoofer", level, 0, 24, 1, colors, Modifier) { n -> scope.launch { tv.setSubwoofer(n) } }
    }
    if (tv.output == null && v == null) {
        Text("The TV didn't send its sound settings. Try again in a moment.", color = colors.inkSoft, fontSize = 12.sp)
    }
}

@Composable
private fun SavingPage(tv: TvSettings, colors: ThemeColors, top: Modifier) {
    if (NotPaired(tv, colors)) return
    val scope = rememberCoroutineScope()
    val current = tv.saving
    if (current == null) {
        Text("The TV didn't send its power saving setting. Try again in a moment.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    ChipRow("Power saving", SAVING, current, colors, top) { v -> scope.launch { tv.setSaving(v) } }
    Text("Picture off keeps the sound playing with the screen dark. Any button on the remote brings the picture back.", color = colors.inkSoft, fontSize = 11.sp)
}

/**
 * Sound on devices without Sony's service: this device's own volume and mute. On an HDMI stick
 * that's the stick's output; the display's own volume is on its remote (HDMI-CEC may pass it on).
 */
@Composable
private fun GenericSoundPage(colors: ThemeColors, top: Modifier) {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(GenericControl.volume(context)) }
    var muted by remember { mutableStateOf(GenericControl.muted(context)) }
    val max = remember { GenericControl.maxVolume(context) }
    // Volume keys change it too; read it again whenever Haven comes back.
    OnResume {
        level = GenericControl.volume(context)
        muted = GenericControl.muted(context)
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (muted) "Muted" else level.toString(), color = colors.ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Volume · this device", color = colors.inkSoft, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    SliderRow("Volume", level, 0, max, 1, colors, top) { n ->
        GenericControl.setVolume(context, n)
        level = GenericControl.volume(context)
    }
    Chip("Mute", muted, colors) {
        GenericControl.toggleMute(context)
        muted = GenericControl.muted(context)
    }
    Text("This changes this device's volume. Your display's own volume is on its remote.", color = colors.inkSoft, fontSize = 11.sp)
}

@Composable
private fun SleepPage(colors: ThemeColors, top: Modifier) {
    val context = LocalContext.current
    var endsAt by remember { mutableStateOf(SleepTimer.endsAt(context)) }
    val choices = listOf("off" to "Off") + SleepTimer.CHOICES.map { it.toString() to "$it min" }
    ChipRow("Turn the TV off in", choices, if (endsAt == null) "off" else "", colors, top) { v ->
        if (v == "off") SleepTimer.cancel(context) else SleepTimer.set(context, v.toInt())
        endsAt = SleepTimer.endsAt(context)
    }
    Text(
        endsAt?.let { "The TV turns off at ${timeText(it)}." } ?: "No sleep timer set.",
        color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
    )
    Text("Haven's own timer: when it runs out, Haven turns the TV off (or puts this device to sleep). A restart clears it.", color = colors.inkSoft, fontSize = 11.sp)
}

fun timeText(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))

/** Network found nearby. */
private data class Nearby(val ssid: String, val level: Int, val secured: Boolean)

@Composable
private fun WifiPage(state: HavenState, colors: ThemeColors, top: Modifier) {
    val context = LocalContext.current
    val wifi = remember { context.applicationContext.getSystemService(WifiManager::class.java) }
    var allowed by remember { mutableStateOf(granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    var nearby by remember { mutableStateOf<List<Nearby>?>(null) }
    val dns by rememberDnsView(state)

    val on = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0
    @Suppress("DEPRECATION") val info = runCatching { wifi.connectionInfo }.getOrNull()
    val ssid = info?.ssid?.trim('"')?.takeIf { allowed && it.isNotBlank() && it != "<unknown ssid>" }
    val connected = info != null && info.networkId != -1
    val bars = info?.let { runCatching { wifi.calculateSignalLevel(it.rssi) to wifi.maxSignalLevel }.getOrNull() }
    val wifiSettings = { openSystemPage(context, Intent(Settings.ACTION_WIFI_SETTINGS)) }

    LaunchedEffect(allowed) {
        if (!allowed) return@LaunchedEffect
        @Suppress("DEPRECATION") runCatching { wifi.startScan() }
        repeat(3) {
            nearby = scanList(wifi)
            delay(2500)
        }
    }

    // Android doesn't let apps switch Wi-Fi, so this row opens Android's Wi-Fi page, where the
    // switch is the first thing.
    LinkRow("Wi-Fi", if (on) "On" else "Off", "Switch it in Wi-Fi settings ›", colors, top, wifiSettings)
    InfoLine(
        "Network",
        when {
            !connected -> "Not connected"
            ssid != null -> ssid
            else -> "Connected (name needs location access)"
        },
        colors,
    )
    bars?.let { (level, max) -> InfoLine("Signal", signalText(level, max), colors) }
    ipAddress(context)?.let { InfoLine("IP address", it, colors) }
    InfoLine("Private DNS", dns.label, colors)

    if (!allowed) {
        Chip("Show nearby networks", false, colors) { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        return
    }
    Eyebrow("Nearby", colors)
    val lm = context.getSystemService(LocationManager::class.java)
    when {
        lm?.isLocationEnabled == false -> Text("Turn on Location in Android settings to list nearby networks.", color = colors.inkSoft, fontSize = 12.sp)
        nearby == null -> Text("Looking…", color = colors.inkSoft, fontSize = 12.sp)
        nearby!!.isEmpty() -> Text("No networks found yet.", color = colors.inkSoft, fontSize = 12.sp)
        else -> nearby!!.forEach { n ->
            val max = runCatching { wifi.maxSignalLevel }.getOrDefault(4)
            LinkRow(
                n.ssid, (if (n.secured) "🔒 " else "") + signalText(n.level, max),
                if (n.ssid == ssid) "Connected" else "Join in Wi-Fi settings ›", colors, Modifier, wifiSettings,
            )
        }
    }
}

/** Quick settings' Wi-Fi & Bluetooth page: a tab for each, as chips across the top. */
@Composable
private fun NetworkPage(state: HavenState, colors: ThemeColors, top: Modifier) {
    var tab by remember { mutableStateOf(TvPage.WIFI) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Wi-Fi", tab == TvPage.WIFI, colors, if (tab == TvPage.WIFI) top else Modifier) { tab = TvPage.WIFI }
        Chip("Bluetooth", tab == TvPage.BLUETOOTH, colors, if (tab == TvPage.BLUETOOTH) top else Modifier) { tab = TvPage.BLUETOOTH }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.edge))
    if (tab == TvPage.WIFI) WifiPage(state, colors, Modifier) else BluetoothPage(colors, Modifier)
}

@Composable
private fun BluetoothPage(colors: ThemeColors, top: Modifier) {
    val context = LocalContext.current
    val bt = rememberBluetooth(ALL_PROFILES)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bt.allowed = it }
    // Set while a device's page is open, so the list is read again on the way back.
    val away = remember { booleanArrayOf(false) }
    val pairNew = {
        openSystemPage(context, Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"))
    }
    OnResume {
        if (!away[0]) return@OnResume
        away[0] = false
        bt.refresh(ALL_PROFILES)
    }

    LinkRow("Bluetooth", if (bt.adapter?.isEnabled == true) "On" else "Off", "Pair a new device ›", colors, top, pairNew)
    // Remotes & Accessories: the Bluetooth switch, Pair accessory and every paired device.
    LinkRow("Bluetooth settings ›", "", "Remotes & Accessories", colors, Modifier) {
        openSystemPage(context, slicePage("general"))
    }
    if (!bt.allowed) {
        Chip("Show paired devices", false, colors) { ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        return
    }
    Eyebrow("Paired", colors)
    if (bt.paired.isEmpty()) Text("No paired devices.", color = colors.inkSoft, fontSize = 12.sp)
    bt.paired.sortedByDescending { it.address in bt.connected }.forEach { d ->
        LinkRow(d.name, if (d.address in bt.connected) "Connected" else "Not connected", "Open in TV settings ›", colors, Modifier) {
            away[0] = true
            openSystemPage(context, devicePage(d))
        }
    }
}

// ---- Bluetooth ----

/** A paired device; [audio] for speakers, headphones and soundbars. */
private data class BtDevice(val name: String, val address: String, val audio: Boolean)

private val AUDIO_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

/** Audio, plus remotes and keyboards (input devices). */
private val ALL_PROFILES = AUDIO_PROFILES + 4

/** Paired devices and which are connected right now, read once BLUETOOTH_CONNECT is granted. */
private class Bluetooth(private val context: Context) {
    val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    var allowed by mutableStateOf(granted(context, Manifest.permission.BLUETOOTH_CONNECT))
    var paired by mutableStateOf<List<BtDevice>>(emptyList())
    var connected by mutableStateOf<Set<String>>(emptySet())

    @SuppressLint("MissingPermission") // only runs once BLUETOOTH_CONNECT is granted
    suspend fun refresh(profiles: List<Int>) {
        val a = adapter
        if (!allowed || a == null) return
        paired = runCatching {
            a.bondedDevices.map {
                BtDevice(it.name ?: it.address, it.address, it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO)
            }
        }.getOrDefault(emptyList())
        connected = profiles.flatMap { connectedOn(a, it) }.toSet()
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedOn(adapter: BluetoothAdapter, profile: Int): List<String> = withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine<List<String>> { cont ->
            val asked = runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val list = runCatching { proxy.connectedDevices.map { it.address } }.getOrDefault(emptyList())
                        adapter.closeProfileProxy(p, proxy)
                        if (cont.isActive) cont.resume(list)
                    }
                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }.getOrDefault(false)
            if (!asked) cont.resume(emptyList())
        }
    } ?: emptyList()
}

@Composable
private fun rememberBluetooth(profiles: List<Int>): Bluetooth {
    val context = LocalContext.current
    val bt = remember { Bluetooth(context) }
    LaunchedEffect(bt.allowed) { bt.refresh(profiles) }
    return bt
}

/** A page of the TV's Settings by its slice (the way the TV's own menus open them). */
private fun slicePage(path: String): Intent =
    Intent("android.settings.SLICE_SETTINGS")
        .setClassName("com.android.tv.settings", "com.android.tv.settings.slice.SliceActivity")
        .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/$path")

/** The TV's own page for a paired device: its name, then Connect (or Disconnect), Rename, Forget. */
private fun devicePage(d: BtDevice): Intent = slicePage("device/${d.address}")

/**
 * Opens [d]'s page and has Haven's Home button service press [action] there, then go Back to
 * Haven. Without the service the page just stays open for you to press it.
 */
private fun pressOnDevicePage(context: Context, d: BtDevice, action: String) {
    HomeOverrideService.pressInTvSettings(action, d.name)
    openSystemPage(context, devicePage(d))
}

/** Runs [action] each time Haven comes back to the front. */
@Composable
private fun OnResume(action: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(action)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { latest() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun scanList(wifi: WifiManager): List<Nearby> = runCatching {
    wifi.scanResults
        .filter { !it.SSID.isNullOrBlank() }
        .groupBy { it.SSID }
        .map { (ssid, results) ->
            val best = results.maxBy { it.level }
            Nearby(ssid, wifi.calculateSignalLevel(best.level), best.capabilities.contains("WPA") || best.capabilities.contains("WEP") || best.capabilities.contains("SAE"))
        }
        .sortedByDescending { it.level }
        .take(8)
}.getOrDefault(emptyList())

private fun signalText(level: Int, max: Int): String {
    val m = max.coerceAtLeast(1)
    return "●".repeat(level.coerceIn(0, m)) + "○".repeat((m - level).coerceIn(0, m))
}

private fun ipAddress(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val props = runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull() ?: return null
    return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
}

// ---- Controls ----

@Composable
private fun InfoLine(label: String, value: String, colors: ThemeColors) {
    SettingsStatusRow(label, value, colors)
}

/** A full-width row that opens something: name, its value, and where OK goes. */
@Composable
private fun LinkRow(title: String, value: String, hint: String, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    SettingsActionRow(title, hint, value, colors, modifier, onClick = onClick)
}

/** A small pill; [selected] gets the accent ring, focus fills it. */
@Composable
private fun Chip(text: String, selected: Boolean, colors: ThemeColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val ink = if (focused) colors.pillInk else colors.ink
    Box(
        modifier
            .settingSearchTarget(text)
            .clip(shape)
            .background(
                when {
                    focused -> colors.pill
                    selected -> colors.pill.copy(alpha = 0.22f)
                    else -> colors.glass
                },
            )
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(text, color = ink, fontSize = 12.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium)
    }
}

/** A name, then its choices as chips; the current one is ringed. */
@Composable
private fun ChipRow(
    label: String,
    choices: List<Pair<String, String>>,
    current: String,
    colors: ThemeColors,
    first: Modifier,
    onPick: (String) -> Unit,
) {
    SettingsChoiceRow(
        label, "", choices.firstOrNull { it.first == current }?.second ?: pretty(current),
        choices.map { (value, text) -> SettingsPickerOption(value, text) }, current, colors, first,
        onChoose = onPick,
    )
}

/**
 * Smart slider: the whole row is one control. Left/Right moves one step, holding speeds up
 * (2, then 5 steps a press); a tap or drag on the track jumps there. Changes reach the TV once
 * you pause, so a held key sends one update instead of dozens.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    colors: ThemeColors,
    modifier: Modifier,
    onChange: (Int) -> Unit,
) {
    var shown by remember { mutableIntStateOf(value) }
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var trackWidth by remember { mutableIntStateOf(1) }
    val focus = remember { FocusRequester() }
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(value) { if (!editing) shown = value }
    LaunchedEffect(shown, editing) {
        if (!editing) return@LaunchedEffect
        delay(220)
        editing = false
        if (shown != value) change(shown)
    }
    fun set(v: Int) {
        shown = v.coerceIn(min, max)
        editing = true
    }
    fun at(x: Float) = set(min + (((x / trackWidth).coerceIn(0f, 1f) * (max - min)) / step).roundToInt() * step)

    val shape = RoundedCornerShape(14.dp)
    val ink = colors.ink
    Row(
        modifier
            .settingSearchTarget(label)
            .fillMaxWidth()
            .height(44.dp)
            .focusRequester(focus)
            .clip(shape)
            .background(colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) {
                    val repeat = e.nativeKeyEvent.repeatCount
                    val mult = when {
                        repeat < 4 -> 1
                        repeat < 12 -> 2
                        else -> 5
                    }
                    set(shown + (if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1) * step * mult)
                }
                true
            }
            .pointerInput(Unit) { detectTapGestures { runCatching { focus.requestFocus() } } }
            .focusable()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.width(96.dp))
        Box(
            Modifier
                .weight(1f)
                .height(26.dp)
                .onSizeChanged { trackWidth = it.width.coerceAtLeast(1) }
                .pointerInput(min, max, step) {
                    detectTapGestures { runCatching { focus.requestFocus() }; at(it.x) }
                }
                .pointerInput(min, max, step) {
                    detectHorizontalDragGestures { change, _ -> at(change.position.x) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val fraction = if (max > min) (shown - min).toFloat() / (max - min) else 0f
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.18f)))
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(50))
                    .background(if (focused) colors.accent else ink.copy(alpha = 0.85f)),
            )
        }
        Text(shown.toString(), color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
}
