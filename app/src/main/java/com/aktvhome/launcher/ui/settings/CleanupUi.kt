@file:OptIn(ExperimentalLayoutApi::class) // FlowRow, for the section chips

package com.aktvhome.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Classified
import com.aktvhome.launcher.data.Cleanup
import com.aktvhome.launcher.data.CleanupCategory
import com.aktvhome.launcher.data.PrivacyKind
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.havenSetting
import com.aktvhome.launcher.ui.theme.ThemeColors

private enum class CleanupTab(val label: String) {
    SCAN("Scan"), PRIVACY("Privacy"),
}

/**
 * The Cleanup tab: section chips across the top (styled like TV controls), the chosen section
 * below. Scan and Privacy only read; they work on every TV.
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
