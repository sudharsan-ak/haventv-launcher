package com.aktvhome.launcher.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.WallpaperFit
import com.aktvhome.launcher.data.WallpaperUploadServer
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

/** Upload dialog never starts a server by itself; the caller owns its lifecycle. */
@Composable
internal fun WallpaperUploadDialog(
    server: WallpaperUploadServer,
    colors: ThemeColors,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val qr = remember(server.address) {
        runCatching {
            val matrix = QRCodeWriter().encode(server.address, BarcodeFormat.QR_CODE, 176, 176)
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) for (x in 0 until matrix.width)
                pixels[y * matrix.width + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
    HavenOverlay(dim = STACKED_DIM, onDismiss = onDismiss) {
        Column(
            Modifier.align(Alignment.Center).width(490.dp)
                .glassPanel(colors, RoundedCornerShape(24.dp), alpha = STACKED_GLASS)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Eyebrow("Wallpaper / My photo", colors)
            Text("Send from phone or PC", color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("Use the same local network. The upload page closes automatically after one photo or five minutes.",
                color = colors.inkSoft, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                qr?.let {
                    Image(it, "Scan to open Haven's local upload page",
                        Modifier.size(176.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit)
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Open on your device:", color = colors.inkSoft, fontSize = 11.sp)
                    Text(server.address, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Pairing code", color = colors.inkSoft, fontSize = 11.sp)
                    Text(server.code, color = colors.ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Choose JPG, PNG or WebP (up to 16 MB), then preview and apply on this TV.",
                color = colors.inkSoft, fontSize = 11.sp)
            if (!server.isRunning) Text("Upload has closed. Open Send from device to try again.",
                color = colors.inkSoft, fontSize = 12.sp)
            Pill("Close", colors, modifier = Modifier.fillMaxWidth(),
                focusRequester = focus, onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Preview first; no existing wallpaper is replaced until Apply is confirmed. */
@Composable
internal fun WallpaperImportPreview(state: HavenState, colors: ThemeColors, onDismiss: () -> Unit) {
    val ready = state.wallpapers.pendingPhoto ?: return
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var fit by remember(ready) { mutableStateOf(state.settingsStore.settings.wallpaperFit) }
    var applying by remember { mutableStateOf(false) }
    HavenOverlay(dim = STACKED_DIM, onDismiss = onDismiss) {
        Column(
            Modifier.align(Alignment.Center).width(530.dp)
                .glassPanel(colors, RoundedCornerShape(24.dp), alpha = STACKED_GLASS)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Eyebrow("Wallpaper / Preview", colors)
            Text("Apply this photo?", color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(ready.name, color = colors.inkSoft, fontSize = 11.sp)
            Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(14.dp))
                .background(Color.Black), contentAlignment = Alignment.Center) {
                Image(ready.image, "Preview of the new wallpaper", Modifier.fillMaxWidth().height(230.dp),
                    contentScale = if (fit == WallpaperFit.FILL) ContentScale.Crop else ContentScale.Fit)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WallpaperFit.entries.forEach { option ->
                    Pill(option.label, colors, selected = option == fit,
                        onClick = { fit = option })
                }
            }
            Text("Fill covers the screen and may crop edges. Fit shows the full photo with unused space around it.",
                color = colors.inkSoft, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (applying) "Saving..." else "Apply wallpaper", colors,
                    focusRequester = focus, onClick = {
                        if (!applying) {
                            applying = true
                            scope.launch {
                                if (state.wallpapers.applyPending(fit)) onDismiss()
                                applying = false
                            }
                        }
                    })
                Pill("Cancel", colors, onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
