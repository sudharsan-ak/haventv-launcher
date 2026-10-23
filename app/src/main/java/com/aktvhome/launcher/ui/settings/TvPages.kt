@file:OptIn(ExperimentalLayoutApi::class) // FlowRow for the choice pills

package com.aktvhome.launcher.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aktvhome.launcher.data.SleepTimer
import com.aktvhome.launcher.data.SonyControl
import com.aktvhome.launcher.data.SonySetting
import com.aktvhome.launcher.data.SonyVolume
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.theme.ThemeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The TV pages that open inside Quick settings. */
enum class TvPage(val title: String) { PICTURE("Picture"), SOUND("Sound"), SAVING("Power saving"), WIFI("Wi-Fi"), SLEEP("Sleep timer") }

/**
 * What the TV's control service reports for picture, sound and power saving, loaded when Quick
 * settings opens and kept in step with each change.
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
        if (!sony.setPicture(target, value)) problem = "The TV didn't accept ${label(target)}."
        else problem = null
        // A new picture mode brings its own brightness, contrast and so on.
        if (target == "pictureMode") sony.pictureSettings()?.let { picture = it }
    }

    suspend fun setOutput(value: String) {
        val ok = sony.setSoundOutput(value)
        problem = if (ok) null else "The TV didn't switch to ${outputLabel(value)}."
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

private val PICTURE_ORDER = listOf(
    "pictureMode", "brightness", "contrast", "color", "sharpness", "hue",
    "colorTemperature", "hdrMode", "lightSensor", "autoLocalDimming", "colorSpace",
)

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

// ---- Pages ----

/** The body of [page], under the Back header. [first] goes on its first control. */
@Composable
fun TvPageBody(page: TvPage, state: HavenState, tv: TvSettings, colors: ThemeColors, first: FocusRequester) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tv.problem?.let { Text(it, color = colors.ink, fontSize = 11.sp) }
        when (page) {
            TvPage.PICTURE -> PicturePage(tv, colors, first)
            TvPage.SOUND -> SoundPage(tv, colors, first)
            TvPage.SAVING -> SavingPage(tv, colors, first)
            TvPage.WIFI -> WifiPage(state, colors, first)
            TvPage.SLEEP -> SleepPage(colors, first)
        }
    }
}

@Composable
private fun NotPaired(tv: TvSettings, colors: ThemeColors, first: FocusRequester): Boolean {
    if (!tv.loaded) {
        Text("Asking the TV…", color = colors.inkSoft, fontSize = 12.sp)
        return true
    }
    if (tv.paired) return false
    Text("Haven isn't paired with the TV yet. Go back and press ⏻ to pair (once).", color = colors.inkSoft, fontSize = 12.sp)
    Pill("OK", colors, focusRequester = first) {}
    return true
}

@Composable
private fun PicturePage(tv: TvSettings, colors: ThemeColors, first: FocusRequester) {
    if (NotPaired(tv, colors, first)) return
    val scope = rememberCoroutineScope()
    val list = tv.picture
    if (list == null) {
        Text("The TV didn't send its picture settings. Try again in a moment.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    val ordered = list.sortedBy { PICTURE_ORDER.indexOf(it.target).let { i -> if (i < 0) 99 else i } }
    ordered.forEachIndexed { i, s ->
        val focus = if (i == 0) first else null
        if (s.isRange) {
            StepperRow(label(s.target), s.current.toIntOrNull() ?: 0, s.min!!, s.max!!, s.step, colors, focus) { v ->
                scope.launch { tv.setPicture(s.target, v.toString()) }
            }
        } else if (s.choices.isNotEmpty()) {
            ChoiceRow(label(s.target), s.choices.map { it to pretty(it) }, s.current, colors, focus) { v ->
                scope.launch { tv.setPicture(s.target, v) }
            }
        }
    }
}

@Composable
private fun SoundPage(tv: TvSettings, colors: ThemeColors, first: FocusRequester) {
    if (NotPaired(tv, colors, first)) return
    val scope = rememberCoroutineScope()
    tv.output?.let { current ->
        ChoiceRow("Output", OUTPUTS, current, colors, first) { v -> scope.launch { tv.setOutput(v) } }
    }
    tv.volume?.let { v ->
        StepperRow("Volume", v.volume, v.min, v.max, 1, colors, if (tv.output == null) first else null) { n ->
            scope.launch { tv.setVolume(n) }
        }
        ChoiceRow("Mute", listOf("on" to "Muted", "off" to "Sound on"), if (v.mute) "on" else "off", colors, null) { pick ->
            if ((pick == "on") != v.mute) scope.launch { tv.toggleMute() }
        }
    }
    // Sony's menu allows 0–24 for the subwoofer.
    tv.subwoofer?.let { level ->
        StepperRow("Subwoofer level", level, 0, 24, 1, colors, null) { n -> scope.launch { tv.setSubwoofer(n) } }
    }
    if (tv.output == null && tv.volume == null) {
        Text("The TV didn't send its sound settings. Try again in a moment.", color = colors.inkSoft, fontSize = 12.sp)
    }
}

@Composable
private fun SavingPage(tv: TvSettings, colors: ThemeColors, first: FocusRequester) {
    if (NotPaired(tv, colors, first)) return
    val scope = rememberCoroutineScope()
    val current = tv.saving
    if (current == null) {
        Text("The TV didn't send its power saving setting. Try again in a moment.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    ChoiceRow("Power saving", SAVING, current, colors, first) { v -> scope.launch { tv.setSaving(v) } }
    Text("Picture off keeps the sound playing with the screen dark. Any button on the remote brings the picture back.", color = colors.inkSoft, fontSize = 11.sp)
}

@Composable
private fun SleepPage(colors: ThemeColors, first: FocusRequester) {
    val context = LocalContext.current
    var endsAt by remember { mutableStateOf(SleepTimer.endsAt(context)) }
    val choices = listOf("off" to "Off") + SleepTimer.CHOICES.map { it.toString() to "$it min" }
    ChoiceRow("Turn the TV off in", choices, if (endsAt == null) "off" else "", colors, first) { v ->
        if (v == "off") SleepTimer.cancel(context) else SleepTimer.set(context, v.toInt())
        endsAt = SleepTimer.endsAt(context)
    }
    Text(
        endsAt?.let { "The TV turns off at ${timeText(it)}." } ?: "No sleep timer set.",
        color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
    )
    Text("Haven's own timer: when it runs out, Haven powers the TV off through its control service. A restart clears it.", color = colors.inkSoft, fontSize = 11.sp)
}

fun timeText(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))

/** Network found nearby. */
private data class Nearby(val ssid: String, val level: Int, val secured: Boolean)

@Composable
private fun WifiPage(state: HavenState, colors: ThemeColors, first: FocusRequester) {
    val context = LocalContext.current
    val wifi = remember { context.applicationContext.getSystemService(WifiManager::class.java) }
    var allowed by remember { mutableStateOf(hasLocation(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    var nearby by remember { mutableStateOf<List<Nearby>?>(null) }
    val dns by rememberDnsView(state)

    val on = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0
    @Suppress("DEPRECATION") val info = runCatching { wifi.connectionInfo }.getOrNull()
    val ssid = info?.ssid?.trim('"')?.takeIf { allowed && it.isNotBlank() && it != "<unknown ssid>" }
    val connected = info != null && info.networkId != -1
    val bars = info?.let { runCatching { wifi.calculateSignalLevel(it.rssi) to wifi.maxSignalLevel }.getOrNull() }

    LaunchedEffect(allowed) {
        if (!allowed) return@LaunchedEffect
        @Suppress("DEPRECATION") runCatching { wifi.startScan() }
        repeat(3) {
            nearby = scanList(wifi)
            delay(2500)
        }
    }

    InfoLine("Wi-Fi", if (on) "On" else "Off", colors)
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

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Wi-Fi settings ›", colors, focusRequester = first) {
            runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (!allowed) Pill("Show nearby networks", colors) { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }
    Text("Turning Wi-Fi on or off and joining a network happen in Wi-Fi settings; Android doesn't let other apps do those.", color = colors.inkSoft, fontSize = 11.sp)

    if (allowed) {
        Eyebrow("Nearby", colors)
        val lm = context.getSystemService(LocationManager::class.java)
        when {
            lm?.isLocationEnabled == false -> Text("Turn on Location in Android settings to list nearby networks.", color = colors.inkSoft, fontSize = 12.sp)
            nearby == null -> Text("Looking…", color = colors.inkSoft, fontSize = 12.sp)
            nearby!!.isEmpty() -> Text("No networks found yet.", color = colors.inkSoft, fontSize = 12.sp)
            else -> nearby!!.forEach { n ->
                val max = runCatching { wifi.maxSignalLevel }.getOrDefault(4)
                GlassCard(colors, Modifier.fillMaxWidth(), onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { ink ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            n.ssid + if (n.ssid == ssid) "  · connected" else "", color = ink, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text((if (n.secured) "🔒 " else "") + signalText(n.level, max), color = ink.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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

// ---- Rows ----

@Composable
private fun InfoLine(label: String, value: String, colors: ThemeColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.inkSoft, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A name, then its choices as pills; the current one is marked. */
@Composable
private fun ChoiceRow(
    label: String,
    choices: List<Pair<String, String>>,
    current: String,
    colors: ThemeColors,
    focus: FocusRequester?,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = colors.inkSoft, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEachIndexed { i, (value, text) ->
                Pill(text, colors, selected = value == current, focusRequester = if (i == 0) focus else null) { onPick(value) }
            }
        }
    }
}

/** A name with − value + controls, one step per press. */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    colors: ThemeColors,
    focus: FocusRequester?,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Pill("−", colors, focusRequester = focus) { if (value > min) onChange((value - step).coerceAtLeast(min)) }
        Text(value.toString(), color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
        Pill("+", colors) { if (value < max) onChange((value + step).coerceAtMost(max)) }
    }
}
