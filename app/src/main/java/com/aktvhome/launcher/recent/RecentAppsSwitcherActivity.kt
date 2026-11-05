package com.aktvhome.launcher.recent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import com.aktvhome.launcher.data.Motion
import com.aktvhome.launcher.data.SettingsStore
import com.aktvhome.launcher.ui.theme.Palettes
import com.aktvhome.launcher.ui.theme.dayness
import com.aktvhome.launcher.ui.settings.glassPanel
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private data class SwitchPreview(val bitmap: Bitmap?, val label: String, val icon: Bitmap?)
private data class SwitcherState(
    val tasks: List<RecentTask> = emptyList(),
    val selected: Int = 0,
    val previews: Map<Int, SwitchPreview> = emptyMap(),
    val labels: Map<Int, String> = emptyMap(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val notice: String = "",
    val needsShizuku: Boolean = false,
    val confirmClearAll: Boolean = false,
    /** 0 = carousel, 1 = Back, 2 = Clear all. */
    val footerFocus: Int = 0,
)

/**
 * First production-facing Apple TV-inspired Recents UI. Uses the same Shizuku task
 * transport already verified in the lab, but NEVER calls removeTask or force-stop.
 * The Activity is translucent and a separate temporary task so Back returns to
 * the previous app; actual behavior with protected apps must be checked on Sony TV.
 */
class RecentAppsSwitcherActivity : ComponentActivity() {
    private val client by lazy { RecentProbeClient(this) }
    private var view by mutableStateOf(SwitcherState())
    private val pendingImages = mutableSetOf<Int>()
    private val settings by lazy { SettingsStore(this).settings }
    /** Same theme and liquid-glass palette as Haven Settings / Quick Settings. */
    private val colors by lazy {
        Palettes.colors(settings.theme, dayness(settings.dayMode, LocalTime.now()))
    }
    private val accent get() = colors.accent
    private val animationsOn get() = settings.motion != Motion.OFF
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { scan() }
    }

    companion object {
        /** Open a temporary switcher task, not a fresh launch of an app. */
        fun launch(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(context, RecentAppsSwitcherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            true
        }.getOrDefault(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A real translucent overlay: the underlying app remains visible wherever
        // Android permits. Android 12 can blur windows behind us; a neutral dim
        // keeps the effect readable when TV firmware disables cross-window blur.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply {
            dimAmount = 0.40f
            setBlurBehindRadius(30)
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeOrCancel()
        })
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = accent)) {
                SwitcherScreen(view)
            }
        }
        scan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scan()
    }

    private fun scan() {
        if (view.busy) return
        view = view.copy(loading = true, needsShizuku = false,
            notice = "Looking for recent Android tasks...")
        lifecycleScope.launch {
            try {
                if (!client.connect()) {
                    view = view.copy(loading = false, needsShizuku = true,
                        notice = "Shizuku is unavailable or needs permission. " +
                            "Task restoration requires Shizuku.")
                    return@launch
                }
                val raw = client.list()
                val current = RecentTaskParser.parse(raw, packageName)
                    .filterNot { RecentHiddenStore.isHidden(this@RecentAppsSwitcherActivity, it.taskId) }
                view = view.copy(tasks = current,
                    selected = RecentSwitcherNavigation.afterHide(view.selected, current.size),
                    loading = false, notice = "", needsShizuku = false,
                    confirmClearAll = false, footerFocus = 0)
                ensureVisiblePreviews()
            } catch (e: Exception) {
                view = view.copy(loading = false, needsShizuku = true,
                    notice = "Could not retrieve Android tasks: " +
                        e.javaClass.simpleName + ". Open Shizuku and try again.")
            }
        }
    }

    private fun ensureVisiblePreviews() {
        val state = view
        state.tasks.forEachIndexed { index, task ->
            if (abs(index - state.selected) > 2 ||
                state.previews.containsKey(task.taskId) ||
                !pendingImages.add(task.taskId)) return@forEachIndexed
            lifecycleScope.launch {
                val preview = withContext(Dispatchers.IO) {
                    val file = RecentPreviewStore.file(applicationContext, task.pkg)
                    val screenshot = if (file.isFile)
                        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                    else null
                    val media = if (screenshot == null)
                        RecentMediaArtwork.find(applicationContext, task.pkg) else null
                    val bitmap = screenshot ?: media?.bitmap
                        ?: RecentMediaArtwork.appFallback(applicationContext, task.pkg)
                    val label = if (screenshot != null) "Screen preview"
                        else if (media?.bitmap != null) media.label else "App artwork"
                    val name = runCatching {
                        val info = packageManager.getApplicationInfo(task.pkg, 0)
                        packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(task.pkg.substringAfterLast('.'))
                    val icon = runCatching {
                        val d = packageManager.getApplicationIcon(task.pkg)
                        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bmp ->
                            d.setBounds(0, 0, 64, 64)
                            d.draw(android.graphics.Canvas(bmp))
                        }
                    }.getOrNull()
                    Pair(SwitchPreview(bitmap, label, icon), name)
                }
                pendingImages.remove(task.taskId)
                if (view.tasks.none { it.taskId == task.taskId }) return@launch
                view = view.copy(
                    previews = view.previews + (task.taskId to preview.first),
                    labels = view.labels + (task.taskId to preview.second),
                )
            }
        }
    }

    private fun select(delta: Int) {
        if (view.tasks.isEmpty() || view.busy) return
        val next = RecentSwitcherNavigation.move(view.selected, delta, view.tasks.size)
        if (next == view.selected) return
        view = view.copy(selected = next, footerFocus = 0, confirmClearAll = false)
        ensureVisiblePreviews()
    }

    private fun resume() {
        if (view.busy || view.loading || view.confirmClearAll ||
            view.footerFocus != 0) return
        val task = view.tasks.getOrNull(view.selected) ?: return
        view = view.copy(busy = true, notice = "Resuming " +
            (view.labels[task.taskId] ?: task.pkg) + "...")
        lifecycleScope.launch {
            try {
                if (client.restore(task.taskId)) {
                    // Android moves the SAME task to front; no fresh launch intent.
                    delay(120)
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                } else {
                    view = view.copy(busy = false,
                        notice = "Could not restore this task. Haven did not relaunch the app.")
                }
            } catch (e: Exception) {
                view = view.copy(busy = false,
                    notice = "Task restore failed: " + e.javaClass.simpleName)
            }
        }
    }

    private fun hideSelected() {
        if (view.busy) return
        val task = view.tasks.getOrNull(view.selected) ?: return
        RecentHiddenStore.hide(this, task)
        val remaining = view.tasks.filterNot { it.taskId == task.taskId }
        view = view.copy(tasks = remaining,
            selected = RecentSwitcherNavigation.afterHide(view.selected, remaining.size),
            notice = "", confirmClearAll = false, footerFocus = 0)
        ensureVisiblePreviews()
    }

    private fun clearAll() {
        RecentHiddenStore.hideAll(this, view.tasks)
        view = view.copy(tasks = emptyList(), selected = 0, notice = "",
            confirmClearAll = false, footerFocus = 0)
    }

    private fun showHidden() {
        RecentHiddenStore.showAll(this)
        scan()
    }

    private fun grantShizuku() {
        runCatching { client.requestPermission() }
            .onFailure { view = view.copy(notice = "Shizuku permission request failed: " +
                it.javaClass.simpleName) }
    }

    private fun requestClearAll() {
        if (view.tasks.isEmpty() || view.busy) return
        if (view.confirmClearAll) clearAll()
        else view = view.copy(confirmClearAll = true, footerFocus = 2)
    }

    private fun focusActions(target: Int) {
        view = view.copy(
            footerFocus = RecentSwitcherNavigation.actionFocus(view.footerFocus,
                if (target == 1) -1 else 1),
            confirmClearAll = false)
    }

    private fun closeOrCancel() {
        if (view.confirmClearAll) {
            view = view.copy(confirmClearAll = false, footerFocus = 2)
        } else finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        val controlled = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK)
        if (key !in controlled) return super.dispatchKeyEvent(event)
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) closeOrCancel()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true

        // Failure/empty states keep their direct retry/restore keyboard actions.
        if (view.needsShizuku || (view.tasks.isEmpty() && !view.loading)) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (view.needsShizuku) grantShizuku() else showHidden()
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (view.needsShizuku) scan()
            }
            return true
        }

        if (view.footerFocus != 0) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> focusActions(1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> focusActions(2)
                KeyEvent.KEYCODE_DPAD_UP -> view = view.copy(
                    footerFocus = 0, confirmClearAll = false)
                KeyEvent.KEYCODE_DPAD_DOWN -> focusActions(2)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> if (view.footerFocus == 1)
                    closeOrCancel() else requestClearAll()
            }
            return true
        }

        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> select(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> select(1)
            // Hide from Haven only, never removeTask/force-stop.
            KeyEvent.KEYCODE_DPAD_UP -> hideSelected()
            // Back / Clear all remain visible; Down moves remote focus to them.
            KeyEvent.KEYCODE_DPAD_DOWN -> focusActions(2)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> resume()
        }
        return true
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        client.close()
        super.onDestroy()
    }

    /** Floating Apple TV-like task stack over the underlying app, not a dashboard. */
    @Composable
    private fun SwitcherScreen(data: SwitcherState) {
        val now = LocalDateTime.now()
        Box(
            Modifier.fillMaxSize()
                // Only a neutral veil: do not draw an opaque blue/gray background.
                // Live window blur and dim are applied by WindowManager above.
                .background(Color.Black.copy(alpha = 0.14f))
        ) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 30.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(now.format(DateTimeFormatter.ofPattern(if (settings.clock24)
                    "HH:mm" else "h:mm a")), color = Color.White,
                    fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                    color = Color.White.copy(alpha = 0.76f), fontSize = 12.sp)
            }

            if (data.needsShizuku) {
                Column(
                    Modifier.align(Alignment.Center)
                        .width(450.dp)
                        .glassPanel(colors, RoundedCornerShape(23.dp), alpha = 0.80f)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Recent apps unavailable", color = colors.ink,
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(data.notice, color = colors.inkSoft,
                        fontSize = 14.sp, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassAction("Grant Shizuku", false, true, ::grantShizuku)
                        GlassAction("Try again", false, true, ::scan)
                    }
                }
            } else if (data.tasks.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(if (data.loading) "Finding recent apps..."
                        else "No Recent Apps", color = Color.White,
                        fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (data.loading) "Checking Android tasks"
                        else "Apps you use will appear here.",
                        color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
                    if (!data.loading) {
                        GlassAction("Show hidden apps", false, true, ::showHidden)
                    }
                }
            } else {
                BoxWithConstraints(
                    Modifier.fillMaxSize()
                        .pointerInput(data.tasks.size) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onHorizontalDrag = { change, amount ->
                                    totalDrag += amount
                                    change.consume()
                                },
                                onDragEnd = {
                                    val step = RecentSwitcherNavigation.swipeStep(
                                        totalDrag, 68.dp.toPx())
                                    if (step != 0) select(step)
                                    totalDrag = 0f
                                },
                                onDragCancel = { totalDrag = 0f },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val cardWidth: Dp = (maxWidth * 0.44f).coerceIn(300.dp, 620.dp)
                    val shift = cardWidth * 0.82f
                    data.tasks.forEachIndexed { index, task ->
                        val distance = index - data.selected
                        if (abs(distance) > 2) return@forEachIndexed
                        key(task.taskId) {
                            val isSelected = distance == 0
                            val x by animateDpAsState(shift * distance.toFloat(),
                                animationSpec = tween(if (animationsOn) 430 else 0),
                                label = "recentCardOffset")
                            val scale by animateFloatAsState(
                                if (isSelected) 1f else if (abs(distance) == 1) 0.86f else 0.74f,
                                animationSpec = tween(if (animationsOn) 430 else 0),
                                label = "recentCardScale")
                            val fade by animateFloatAsState(
                                if (isSelected) 1f else if (abs(distance) == 1) 0.82f else 0.48f,
                                animationSpec = tween(if (animationsOn) 350 else 0),
                                label = "recentCardFade")
                            val preview = data.previews[task.taskId]
                            val name = data.labels[task.taskId]
                                ?: task.pkg.substringAfterLast('.')
                            Column(
                                Modifier.align(Alignment.Center)
                                    .offset(x = x, y = 7.dp)
                                    .width(cardWidth)
                                    .zIndex(if (isSelected) 5f else if (abs(distance) == 1) 3f else 1f)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = fade
                                        rotationY = if (isSelected) 0f else
                                            if (distance < 0) 5f else -5f
                                    }
                                    .clickable {
                                        if (isSelected) resume()
                                        else select(distance)
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // Name/icon label: focused card only, like Apple TV's
                                // switcher. Neighbors carry no header row.
                                Row(
                                    Modifier.padding(bottom = 13.dp)
                                        .graphicsLayer { alpha = if (isSelected) 1f else 0f },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (preview?.icon != null) {
                                        Image(preview.icon.asImageBitmap(), null,
                                            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                                    } else {
                                        Box(
                                            Modifier.size(30.dp)
                                                .glassPanel(colors, RoundedCornerShape(8.dp),
                                                    alpha = 0.68f),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(name.take(1).uppercase(), color = colors.ink,
                                                fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(name, color = Color.White, fontSize = 19.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                val shape = RoundedCornerShape(18.dp)
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                        .shadow(if (isSelected) 30.dp else 14.dp,
                                            shape, ambientColor = Color.Black,
                                            spotColor = Color.Black)
                                        .clip(shape)
                                        .background(colors.glass.copy(alpha = 0.26f))
                                        .border(if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) Color.White.copy(alpha = 0.94f)
                                            else Color.White.copy(alpha = 0.24f), shape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val image = preview?.bitmap
                                    if (image != null) {
                                        Image(image.asImageBitmap(), name,
                                            Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop)
                                    } else {
                                        // No screenshot or media artwork: a composed icon
                                        // card, not a raw icon floating on flat glass.
                                        Box(
                                            Modifier.fillMaxSize()
                                                .background(Brush.radialGradient(
                                                    colors = listOf(
                                                        Color.White.copy(alpha = 0.08f),
                                                        Color.Transparent),
                                                    radius = 900f))
                                                .background(Brush.linearGradient(
                                                    colors = listOf(
                                                        colors.glass.copy(alpha = 0.34f),
                                                        colors.ink.copy(alpha = 0.22f)))),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (preview?.icon != null) {
                                                Image(preview.icon.asImageBitmap(), name,
                                                    Modifier.size(cardWidth * 0.26f),
                                                    contentScale = ContentScale.Fit)
                                            } else {
                                                Text(name.take(1).uppercase(), color = colors.ink,
                                                    fontSize = 64.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    // Outline stays on top of the preview, never tinting its pixels.
                                    Box(Modifier.fillMaxSize().border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) Color.White.copy(alpha = 0.95f)
                                        else colors.edge, shape))
                                }
                            }
                        }
                    }
                }
            }

            // Only the two compact, always-visible liquid-glass controls.
            Row(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassAction("Back", data.footerFocus == 1, true,
                    onClick = ::closeOrCancel)
                GlassAction(if (data.confirmClearAll) "Confirm clear" else "Clear all",
                    data.footerFocus == 2, data.tasks.isNotEmpty(),
                    showDeleteIcon = true, onClick = ::requestClearAll)
            }
            // Errors are transient; never reintroduce the permanent instruction strip.
            if (data.notice.isNotBlank() && !data.loading && !data.needsShizuku) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 25.dp)
                        .glassPanel(colors, RoundedCornerShape(14.dp), alpha = 0.81f)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(data.notice, color = colors.ink, fontSize = 12.sp)
                }
            }
        }
    }

    /** Shared with Haven's settings treatment, not a separate Recents color theme. */
    @Composable
    private fun GlassAction(
        label: String,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
        showDeleteIcon: Boolean = false,
    ) {
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .glassPanel(colors, shape, alpha = if (selected) 0.86f else 0.72f)
                .then(if (selected) Modifier.border(2.dp, colors.accent, shape)
                    else Modifier)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDeleteIcon) {
                Icon(Icons.Rounded.DeleteOutline, null,
                    tint = colors.ink.copy(alpha = if (enabled) 1f else 0.48f),
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(label,
                color = colors.ink.copy(alpha = if (enabled) 1f else 0.48f),
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}
