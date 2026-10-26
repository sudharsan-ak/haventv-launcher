@file:OptIn(ExperimentalLayoutApi::class) // FlowRow, for the section chips

package com.aktvhome.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Classified
import com.aktvhome.launcher.data.Cleanup
import com.aktvhome.launcher.data.CleanupCategory
import com.aktvhome.launcher.data.CleanupPlan
import com.aktvhome.launcher.data.CleanupRoute
import com.aktvhome.launcher.data.CleanupRunner
import com.aktvhome.launcher.data.PrivacyKind
import com.aktvhome.launcher.data.Setup
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.SettingsDestination
import com.aktvhome.launcher.ui.havenSetting
import com.aktvhome.launcher.ui.theme.ThemeColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class CleanupTab(val label: String) {
    SCAN("Scan"), PRIVACY("Privacy"), DISABLE("Disable"), RESTORE("Restore"),
}

/**
 * The Cleanup tab: section chips across the top (styled like TV controls), the chosen section
 * below. Scan and Privacy only read; they work on every TV. Disable and Restore change packages
 * through the one-time setup's route (root, or this TV's own USB/ADB debugging).
 */
@Composable
fun CleanupPage(state: HavenState, colors: ThemeColors, first: Modifier) {
    val packages = state.packages
    val requestedSubsection = LocalSettingSearchTarget.current?.settingId?.let(::havenSetting)?.subsection
    var tab by remember(requestedSubsection) {
        mutableStateOf(CleanupTab.entries.firstOrNull { it.name == requestedSubsection } ?: CleanupTab.SCAN)
    }
    LaunchedEffect(Unit) { packages.refresh() }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CleanupTab.entries.forEachIndexed { i, t ->
            SectionChip(t.label, t == tab, colors, if (i == 0) first else Modifier) { tab = t }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.edge))
    if (!packages.loaded) {
        Text("Looking at what's installed…", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    when (tab) {
        CleanupTab.SCAN -> ScanSection(packages.scan, colors)
        CleanupTab.PRIVACY -> PrivacySection(packages.scan, colors)
        CleanupTab.DISABLE -> DisableSection(state, packages.scan, colors)
        CleanupTab.RESTORE -> RestoreSection(state, packages.scan, colors)
    }
}

// ---- Sections ----

@Composable
private fun ScanSection(scan: List<Classified>, colors: ThemeColors) {
    val counts = Cleanup.counts(scan)
    // Opens on the first category with something in it; System last, since it's the long one.
    var category by rememberSaveable { mutableStateOf(CleanupCategory.entries.firstOrNull { (counts[it] ?: 0) > 0 } ?: CleanupCategory.SYSTEM) }
    val shown = scan.filter { it.category == category }
    SettingsChoiceRow(
        "Category", "${scan.size} packages on this TV, ${scan.count { !it.facts.state.on }} switched off",
        "${category.label} (${shown.size})",
        CleanupCategory.entries.map { c -> SettingsPickerOption(c.name, "${c.label} (${counts[c] ?: 0})", c.note) },
        category.name, colors,
    ) { value -> category = CleanupCategory.valueOf(value) }
    if (shown.isEmpty()) {
        SettingsStatusRow(category.label, "Nothing on this TV.", colors)
        return
    }
    shown.forEach { c ->
        SettingsStatusRow(
            c.facts.label,
            listOfNotNull("${c.facts.state.label} · ${c.facts.pkg}", c.note).joinToString("\n"),
            colors, traversable = true,
        )
    }
}

/** What the Privacy filter can show: everything, or one kind. */
private val PRIVACY_FILTERS = listOf<PrivacyKind?>(null) + PrivacyKind.entries

@Composable
private fun PrivacySection(scan: List<Classified>, colors: ThemeColors) {
    val findings = remember(scan) { Cleanup.privacyFindings(scan) }
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = PrivacyKind.entries.firstOrNull { it.name == kindName }
    val shown = findings.filter { kind == null || kind in it.kinds }
    fun label(k: PrivacyKind?) = k?.label ?: "Everything"
    SettingsChoiceRow(
        "Show", "Trackers, telemetry and packages that ask for the microphone", "${label(kind)} (${shown.size})",
        PRIVACY_FILTERS.map { k -> SettingsPickerOption(k?.name ?: ALL, "${label(k)} (${findings.count { k == null || k in it.kinds }})") },
        kindName ?: ALL, colors,
    ) { value -> kindName = value.takeIf { it != ALL } }
    SettingsStatusRow(
        "What this shows",
        "What each package asks Android for, and Haven's own notes. Asking for the microphone isn't using it: " +
            "Haven can't see whether a package is listening, only what it's allowed to do.",
        colors, traversable = true,
    )
    if (shown.isEmpty()) {
        SettingsStatusRow(label(kind), "Nothing on this TV.", colors)
        return
    }
    shown.forEach { f ->
        SettingsStatusRow(
            f.facts.label,
            (listOf("${f.kinds.joinToString { it.label }} · ${f.facts.state.label} · ${f.facts.pkg}") + f.evidence).joinToString("\n"),
            colors, traversable = true,
        )
    }
}

private const val ALL = "ALL"

@Composable
private fun DisableSection(state: HavenState, scan: List<Classified>, colors: ThemeColors) {
    val runner = state.cleanup
    LaunchedEffect(Unit) { runner.probe() }
    when (runner.route) {
        null -> {
            Text("Checking how Haven can reach this TV…", color = colors.inkSoft, fontSize = 12.sp)
            return
        }
        CleanupRoute.WIRELESS_ONLY -> {
            SettingsStatusRow(
                "Not available on this TV",
                "This TV only offers Wireless debugging, which Haven can't use yet. Disable needs root or this TV's own " +
                    "USB/ADB debugging, so nothing here can be switched off. Scan and Privacy still work.",
                colors, traversable = true,
            )
            return
        }
        CleanupRoute.DEBUGGING_OFF -> SettingsStatusRow(
            "Needs debugging",
            "Disable needs root or this TV's own USB/ADB debugging, and neither is on right now. Turn on USB debugging in " +
                "Developer options, then come back. The TV asks once whether to allow Haven: choose Always allow.",
            colors, traversable = true,
        )
        else -> Unit
    }
    SettingsStatusRow(
        "How it works",
        "Packages are switched off for you, never uninstalled. Haven saves each one's state first, so Restore can put it back. " +
            "Core Android, Home, Wi-Fi setup and what Haven needs are never offered.",
        colors, traversable = true,
    )
    val guard = remember(scan) { state.packages.guardContext() }
    val offers = remember(scan, guard) { CleanupPlan.offers(scan, guard) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val picked = offers.filter { it.facts.pkg in chosen }
    CleanupPlan.DISABLE_CATEGORIES.forEach { cat ->
        val items = offers.filter { it.category == cat }
        SettingsMultiChoiceRow(
            cat.label, if (items.isEmpty()) "Nothing on this TV to switch off" else cat.note,
            if (items.isEmpty()) "None" else "${items.count { it.facts.pkg in chosen }} of ${items.size}",
            items.map { c -> SettingsPickerOption(c.facts.pkg, c.facts.label, listOfNotNull(c.facts.pkg, c.note).joinToString(" · ")) },
            chosen, colors, enabled = items.isNotEmpty() && !runner.busy,
        ) { p -> chosen = if (p in chosen) chosen - p else chosen + p }
    }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(chosen) { confirm = false }
    // Stays focusable with nothing chosen, so focus never drops out of the page under you.
    SettingsActionRow(
        "Disable selected",
        if (picked.isEmpty()) "Choose packages above" else picked.joinToString { it.facts.label },
        when {
            runner.busy -> "Working…"
            picked.isEmpty() -> "None chosen"
            confirm -> "Press again to disable"
            else -> "Disable ${picked.size}"
        },
        colors,
    ) {
        when {
            runner.busy || picked.isEmpty() -> Unit
            confirm -> {
                runner.disable(picked.map { it.facts.pkg })
                chosen = emptySet()
            }
            else -> confirm = true
        }
    }
    RunStatus(runner, colors)
    val guarded = remember(scan, guard) { CleanupPlan.guardedCounts(scan, guard) }
    if (guarded.isNotEmpty()) {
        SettingsStatusRow(
            "Never offered",
            guarded.entries.sortedBy { it.key.ordinal }.joinToString("\n") { (reason, n) -> "${reason.label}: $n" },
            colors, traversable = true,
        )
    }
    RunLog(runner, colors)
}

@Composable
private fun RestoreSection(state: HavenState, scan: List<Classified>, colors: ThemeColors) {
    val runner = state.cleanup
    LaunchedEffect(Unit) { runner.check() }
    val labels = remember(scan) { scan.associate { it.facts.pkg to it.facts.label } }
    val restoreAll = remember { FocusRequester() }
    val record = runner.record.values.sortedBy { (labels[it.pkg] ?: it.pkg).lowercase() }
    val (elsewhere, asShipped) = remember(scan, runner.record) { CleanupPlan.offOutsideRecord(scan, runner.record.keys) }
    SettingsStatusRow(
        "How restore works",
        "Restore puts each package back the way it was before Haven switched it off. Like Disable, it needs root or this TV's " +
            "own USB/ADB debugging. Without either, use the TV's own Settings › Apps: open the app (system apps are under " +
            "See all apps) and choose Enable.",
        colors, traversable = true,
    )
    SettingsStatusRow(
        "Off on this TV",
        "${scan.count { !it.facts.state.on }} packages: ${record.size} switched off by Haven, ${elsewhere.size} switched off " +
            "somewhere else, ${asShipped.size} off as the TV came",
        colors, traversable = true,
    )
    val back = runner.cameBack
    SettingsActionRow(
        "Check after an update",
        if (back.isEmpty()) "Nothing Haven switched off has come back on"
        else "${back.size} came back on, often after a firmware update: ${back.joinToString { labels[it.pkg] ?: it.pkg }}",
        if (runner.busy) "Working…" else if (back.isEmpty()) "Check again" else "Disable again",
        colors,
    ) {
        when {
            runner.busy -> Unit
            back.isEmpty() -> runner.check()
            else -> runner.disableAgain()
        }
    }
    SettingsActionRow(
        "Restore everything",
        if (record.isEmpty()) "Nothing to restore" else "Puts all ${record.size} back",
        if (runner.busy) "Working…" else "Restore all",
        colors, Modifier.focusRequester(restoreAll),
    ) { if (!runner.busy) runner.restoreAll() }
    RunStatus(runner, colors)
    record.forEach { e ->
        SettingsActionRow(
            labels[e.pkg] ?: e.pkg,
            "${e.pkg} · was ${e.prior.label} · switched off ${DAY.format(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()))}" +
                if (e in back) " · on again" else "",
            "Restore", colors,
        ) {
            if (!runner.busy) {
                // This row goes once it's restored; focus waits on Restore everything.
                runCatching { restoreAll.requestFocus() }
                runner.restore(listOf(e.pkg))
            }
        }
    }
    if (elsewhere.isNotEmpty()) Eyebrow("Switched off somewhere else", colors)
    elsewhere.forEach { c ->
        SettingsActionRow(
            c.facts.label,
            "${c.facts.pkg} · switched off outside Haven (the TV's settings, a computer, or before Haven kept a record)",
            "Turn on", colors,
        ) {
            if (!runner.busy) {
                runCatching { restoreAll.requestFocus() }
                runner.restore(listOf(c.facts.pkg))
            }
        }
    }
    if (asShipped.isNotEmpty()) Eyebrow("Off as the TV came", colors)
    asShipped.forEach { c ->
        SettingsStatusRow(
            c.facts.label,
            "${c.facts.pkg} · the TV maker ships it off, or Android keeps it off until something needs it. " +
                "Haven leaves these alone: some start store or factory modes.",
            colors, traversable = true,
        )
    }
    RunLog(runner, colors)
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** What's happening now, or how the last run ended. */
@Composable
private fun RunStatus(runner: CleanupRunner, colors: ThemeColors) {
    if (runner.message.isNotBlank()) SettingsStatusRow(if (runner.busy) "Now" else "Last run", runner.message, colors)
}

@Composable
private fun RunLog(runner: CleanupRunner, colors: ThemeColors) {
    if (runner.logLines.isNotEmpty()) SettingsStatusRow("Log", runner.logLines.takeLast(30).joinToString("\n"), colors, traversable = true)
}

/**
 * Offered once per firmware update, when something Cleanup switched off is on again. Review opens
 * Cleanup › Restore; Later leaves it there.
 */
@Composable
fun CleanupUpdateOffer(state: HavenState, colors: ThemeColors) {
    val cleanup = state.cleanup
    if (!cleanup.offerUpdate || state.overlay != Overlay.NONE || state.backup.offerRestore || state.wallpaperView) return
    // The setup offer goes first.
    if (!state.setup.offered && Setup.needed(state.device.status).isNotEmpty()) return
    val first = remember { FocusRequester() }
    val back = cleanup.cameBack
    HavenOverlay(dim = 0.5f, onDismiss = { cleanup.dismissUpdateOffer() }) {
        Column(
            Modifier.align(Alignment.Center).width(560.dp).glassPanel(colors, RoundedCornerShape(26.dp), alpha = 0.96f).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("Haven / Cleanup", colors)
            Text("Some packages came back on", color = colors.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "${if (back.size == 1) "1 package" else "${back.size} packages"} Haven switched off ${if (back.size == 1) "is" else "are"} " +
                    "on again, probably after a firmware update: ${back.take(3).joinToString { it.pkg }}${if (back.size > 3) "…" else ""}. " +
                    "You can switch them off again in Cleanup › Restore.",
                color = colors.inkSoft, fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Review", colors, selected = true, focusRequester = first) {
                    cleanup.dismissUpdateOffer()
                    state.openSettings(SettingsDestination.CLEANUP, "cleanup_recheck")
                }
                Pill("Later", colors) { cleanup.dismissUpdateOffer() }
            }
            Text("Later: it stays in All settings › TV & network › Cleanup › Restore.", color = colors.inkSoft, fontSize = 11.sp)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
