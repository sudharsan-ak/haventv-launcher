package com.aktvhome.launcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.theme.ThemeColors
import java.text.DateFormat
import java.util.Date

fun backupTimeText(ms: Long): String =
    if (ms <= 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** Shown when Haven starts with no settings but finds a backup: restore it, or start fresh. */
@Composable
fun RestorePrompt(state: HavenState, colors: ThemeColors) {
    val backup = state.backup
    val first = remember { FocusRequester() }
    HavenOverlay(dim = 0.5f, onDismiss = {}) {
        Column(
            Modifier.align(Alignment.Center).width(560.dp).glassPanel(colors, RoundedCornerShape(26.dp), alpha = 0.96f).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("Haven / Backup", colors)
            Text("Restore your setup?", color = colors.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Haven started with no settings, but a backup from ${backupTimeText(backup.backupTime())} is saved on this TV. " +
                    "Restoring brings back your theme, wallpaper, collections, Watch Now and everything else.",
                color = colors.inkSoft, fontSize = 14.sp,
            )
            if (backup.problem.isNotBlank()) Text(backup.problem, color = colors.ink, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Restore my setup", colors, selected = true, focusRequester = first) { backup.restore() }
                Pill("Start fresh", colors) { backup.startFresh() }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
