package com.aktvhome.launcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.NbaRotationInterval
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.HavenSettingSpec
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.watch.NetImage

internal enum class NbaLeagueSection(val label: String) {
    OVERVIEW("Overview"), HOME_CARD("Home card"), ALERTS("Alerts");

    companion object {
        /** Use the search catalog's real destination path, not a fragile ID allowlist. */
        fun fromSetting(setting: HavenSettingSpec?): NbaLeagueSection = when {
            setting?.path?.endsWith("· Alerts") == true -> ALERTS
            setting?.path?.endsWith("· Home card") == true -> HOME_CARD
            else -> OVERVIEW
        }
    }
}

/** A dedicated league page. The existing glassy picker host is provided by Personalize. */
@Composable
internal fun NbaLeaguePage(
    state: HavenState, colors: ThemeColors, first: Modifier,
    startingTab: NbaLeagueSection = NbaLeagueSection.OVERVIEW,
) {
    val store = state.settingsStore
    val s = store.settings
    var tab by remember { mutableStateOf(startingTab) }

    SettingsToggleRow(
        "Enable NBA League",
        if (s.nbaLeagueEnabled) "Scores, schedules and alerts are available"
            else "NBA is paused. All your choices are saved.",
        s.nbaLeagueEnabled, colors, modifier = first,
        leading = { NetImage(state.watchNow, Nba.LEAGUE_LOGO, 30.dp) },
    ) { store.update { it.copy(nbaLeagueEnabled = !it.nbaLeagueEnabled) } }

    // The section tabs remain reachable even while NBA is turned off.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NbaLeagueSection.entries.forEach { section ->
            Pill(section.label, colors, selected = tab == section,
                onFocused = { tab = section },
                onClick = { tab = section })
        }
    }

    when (tab) {
        NbaLeagueSection.OVERVIEW -> {
            SettingsChoiceRow(
                "Favorite team", "Shown in My team, the schedule, and Home headlines.",
                if (s.favoriteTeam.isBlank()) "None" else Nba.teamName(s.favoriteTeam),
                listOf(SettingsPickerOption("", "None")) + Nba.TEAMS.map { (abbr, name) ->
                    SettingsPickerOption(abbr, name,
                        leading = { NetImage(state.watchNow, Nba.logo(abbr), 24.dp) })
                },
                s.favoriteTeam, colors, enabled = s.nbaLeagueEnabled,
            ) { team -> store.update { it.copy(favoriteTeam = team) } }

            SettingsMultiChoiceRow(
                "Teams you follow", "Receive your-team alerts for these teams.",
                when (s.followedTeams.size) {
                    0 -> "Pick teams"
                    1 -> Nba.teamName(s.followedTeams.first())
                    else -> "${s.followedTeams.size} teams"
                },
                Nba.TEAMS.map { (abbr, name) ->
                    SettingsPickerOption(abbr, name,
                        leading = { NetImage(state.watchNow, Nba.logo(abbr), 24.dp) })
                },
                s.followedTeams, colors, enabled = s.nbaLeagueEnabled,
            ) { team -> store.update {
                it.copy(followedTeams = if (team in it.followedTeams) it.followedTeams - team
                    else it.followedTeams + team)
            } }
            SettingsStatusRow("League", if (s.nbaLeagueEnabled) "NBA · ESPN scoreboard"
                else "Paused · no NBA background activity", colors)
        }

        NbaLeagueSection.HOME_CARD -> {
            SettingsToggleRow(
                "Show NBA card on Home",
                "Only affects Watch Now; NBA alerts can remain active.",
                s.watchNba, colors, enabled = s.nbaLeagueEnabled,
            ) { store.update { it.copy(watchNba = !it.watchNba) } }

            SettingsToggleRow(
                "Rotate NBA games",
                "Cycle through the day's games. Pauses when the card has focus.",
                s.rotateNba, colors, enabled = s.nbaLeagueEnabled && s.watchNba,
            ) { store.update { it.copy(rotateNba = !it.rotateNba) } }

            SettingsChoiceRow(
                "Rotation interval",
                "Time for each game after the featured game's longer hold.",
                s.nbaRotationInterval.label,
                NbaRotationInterval.entries.map { SettingsPickerOption(it.name, it.label) },
                s.nbaRotationInterval.name, colors,
                enabled = s.nbaLeagueEnabled && s.watchNba && s.rotateNba,
            ) { value -> store.update {
                it.copy(nbaRotationInterval = NbaRotationInterval.valueOf(value))
            } }
            SettingsStatusRow("Featured game", "20 seconds for 2–4 games, 30 seconds for 5+ games", colors)
        }

        NbaLeagueSection.ALERTS -> NbaAlertsPage(state, colors, Modifier, enabled = s.nbaLeagueEnabled)
    }
}
