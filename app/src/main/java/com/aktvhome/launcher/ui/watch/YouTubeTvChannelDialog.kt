package com.aktvhome.launcher.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.TvItem
import com.aktvhome.launcher.ui.GlassDialog
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.theme.ThemeColors

/** Small, accessible TV dialog, not a replacement for YouTube TV's private guide. */
@Composable
fun YouTubeTvChannelDialog(
    colors: ThemeColors,
    channels: List<TvItem>,
    onPick: (TvItem) -> Unit,
    onGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    GlassDialog(colors, "Choose YouTube TV channel", onDismiss) {
        Text(
            "Possible channels for this broadcaster. Haven cannot verify what's airing on each.",
            color = colors.inkSoft, fontSize = 12.sp,
        )
        Column(
            Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            channels.forEachIndexed { index, item ->
                Pill(
                    item.title, colors, modifier = Modifier.fillMaxWidth(),
                    focusRequester = if (index == 0) first else null,
                ) { onPick(item) }
            }
            if (channels.isEmpty()) {
                Text("No matching channel links discovered yet.", color = colors.inkSoft, fontSize = 12.sp)
            }
            Pill(
                "Browse full YouTube TV guide", colors, modifier = Modifier.fillMaxWidth(),
                focusRequester = if (channels.isEmpty()) first else null,
                onClick = onGuide,
            )
        }
        Pill("Cancel", colors, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
    }
    LaunchedEffect(channels.size) {
        kotlinx.coroutines.delay(70)
        runCatching { first.requestFocus() }
    }
}
