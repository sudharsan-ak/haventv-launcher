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
import com.aktvhome.launcher.data.ThemeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private data class SwitchPreview(val bitmap: Bitmap?, val label: String)
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
    private val accent by lazy {
        if (SettingsStore(this).settings.theme == ThemeId.TIDE) Color(0xFF93C5FF)
        else Color(0xFF92F5E7)
    }
    private val animationsOn by lazy {
        SettingsStore(this).settings.motion != Motion.OFF
    }
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
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attrs = window.attributes
        attrs.dimAmount = 0.62f
        window.attributes = attrs
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
                    confirmClearAll = false)
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
                    Pair(SwitchPreview(bitmap, label), name)
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
        view = view.copy(selected = next)
        ensureVisiblePreviews()
    }

    private fun resume() {
        if (view.busy || view.loading || view.confirmClearAll) return
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
            notice = "", confirmClearAll = false)
        ensureVisiblePreviews()
    }

    private fun clearAll() {
        RecentHiddenStore.hideAll(this, view.tasks)
        view = view.copy(tasks = emptyList(), selected = 0, notice = "",
            confirmClearAll = false)
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

    private fun closeOrCancel() {
        if (view.confirmClearAll) view = view.copy(confirmClearAll = false)
        else finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controlled = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK)
        if (event.keyCode !in controlled) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) closeOrCancel()
            return true
        }
        if (view.needsShizuku || (view.tasks.isEmpty() && !view.loading)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> if (view.needsShizuku) grantShizuku()
                        else showHidden()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (view.needsShizuku) scan()
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> closeOrCancel()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!view.confirmClearAll) select(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!view.confirmClearAll) select(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (view.confirmClearAll)
                view = view.copy(confirmClearAll = false) else hideSelected()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (view.tasks.isNotEmpty())
                view = view.copy(confirmClearAll = true)
            else -> if (view.confirmClearAll) clearAll() else resume()
        }
        return true
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        client.close()
        super.onDestroy()
    }

    @Composable
    private fun SwitcherScreen(data: SwitcherState) {
        val now = LocalDateTime.now()
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xE9081724), Color(0xEE07111D),
                    Color(0xF5040A14)))
            )
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("▣", fontSize = 25.sp, color = accent)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("H A V E N", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = Color.White)
                        Text("RECENT APPS", fontSize = 11.sp, color = Color(0xFF9AB0C5))
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(now.format(DateTimeFormatter.ofPattern("h:mm a")),
                            color = Color.White, fontSize = 16.sp)
                        Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                            color = Color(0xFFA4B4C6), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("SWITCH BETWEEN APPS", color = accent, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                Text("Pick up where you left off", color = Color.White, fontSize = 31.sp,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("Your recent apps, right where you left them",
                    color = Color(0xFF9EAEBD), fontSize = 13.sp)

                if (data.needsShizuku) {
                    Column(Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Recent tasks unavailable", color = Color.White, fontSize = 24.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(data.notice, color = Color(0xFFABC1CB), fontSize = 14.sp,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = ::grantShizuku) { Text("Grant Shizuku") }
                            Button(onClick = ::scan) { Text("Try again") }
                        }
                    }
                } else if (data.tasks.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▤", color = accent, fontSize = 48.sp)
                        Text(if (data.loading) "Finding recent apps..."
                            else "No Recent Apps", color = Color.White, fontSize = 25.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(if (data.loading) "Checking Android tasks"
                            else "Apps you use will appear here.",
                            color = Color(0xFF9CACC0), fontSize = 14.sp)
                        if (!data.loading) {
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = ::showHidden) { Text("Show hidden apps") }
                        }
                    }
                } else {
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val cardWidth: Dp = (maxWidth * 0.52f).coerceIn(280.dp, 620.dp)
                        val shift = cardWidth * 0.79f
                        data.tasks.forEachIndexed { index, task ->
                            val diff = index - data.selected
                            if (abs(diff) > 2) return@forEachIndexed
                            key(task.taskId) {
                            val current = diff == 0
                            val targetOffset = shift * diff.toFloat()
                            val x by animateDpAsState(targetOffset,
                                animationSpec = tween(if (animationsOn) 340 else 0),
                                label = "cardX")
                            val scale by animateFloatAsState(if (current) 1f else 0.83f,
                                animationSpec = tween(if (animationsOn) 340 else 0),
                                label = "cardScale")
                            val faded by animateFloatAsState(if (current) 1f else 0.55f,
                                animationSpec = tween(if (animationsOn) 280 else 0),
                                label = "cardFade")
                            val shown = data.previews[task.taskId]
                            val name = data.labels[task.taskId]
                                ?: task.pkg.substringAfterLast('.')
                            Column(
                                Modifier.align(Alignment.Center).offset(x = x)
                                    .width(cardWidth)
                                    .zIndex(if (current) 5f else if (abs(diff) == 1) 3f else 1f)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = faded
                                        rotationY = if (current) 0f else if (diff < 0) 9f else -9f
                                    }
                                    .clickable {
                                        if (current) resume() else select(diff)
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                        .shadow(if (current) 23.dp else 9.dp,
                                            RoundedCornerShape(19.dp))
                                        .clip(RoundedCornerShape(19.dp))
                                        .background(Color(0xFF162536))
                                        .border(if (current) 2.5.dp else 1.dp,
                                            if (current) accent else Color(0x66C2D1E5),
                                            RoundedCornerShape(19.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val image = shown?.bitmap
                                    if (image != null) {
                                        Image(image.asImageBitmap(), name,
                                            Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop)
                                    } else {
                                        Text("▣", color = accent.copy(alpha = 0.7f),
                                            fontSize = 52.sp)
                                    }
                                    Box(Modifier.fillMaxWidth().height(76.dp)
                                        .align(Alignment.BottomCenter)
                                        .background(Brush.verticalGradient(listOf(
                                            Color.Transparent, Color(0xCC030913)))))
                                    if (current) {
                                        Text(shown?.label ?: "Loading preview",
                                            modifier = Modifier.align(Alignment.TopEnd)
                                                .padding(13.dp)
                                                .background(Color(0xA70C1A28),
                                                    RoundedCornerShape(10.dp))
                                                .padding(horizontal = 9.dp, vertical = 5.dp),
                                            color = Color(0xFFDDEBF0), fontSize = 10.sp)
                                    }
                                }
                                Spacer(Modifier.height(17.dp))
                                Text(name, color = Color.White,
                                    fontSize = if (current) 21.sp else 17.sp,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(5.dp))
                                Text(if (current) "Press OK to resume" else "Recent app",
                                    color = if (current) accent else Color(0xFF9AA9BA),
                                    fontSize = 11.sp)
                            }
                            }
                        }
                    }
                }
                if (data.confirmClearAll) {
                    Text("Hide all these cards from Haven Recents? Apps will not be stopped.",
                        color = Color(0xFFFFDCB2), fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = ::clearAll) { Text("Hide all") }
                        Button(onClick = { view = view.copy(confirmClearAll = false) }) {
                            Text("Cancel")
                        }
                    }
                } else if (data.notice.isNotBlank() && !data.loading && !data.needsShizuku) {
                    Text(data.notice, color = Color(0xFFF0C8A0), fontSize = 13.sp)
                }
                Spacer(Modifier.height(13.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(Color(0x80233647)).border(1.dp,
                            Color(0x444B7485), RoundedCornerShape(24.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FooterLabel("◀ ▶  Switch")
                    FooterLabel("OK  Resume")
                    FooterLabel("↑  Remove from Recents")
                    FooterLabel("↓  Clear all")
                    FooterLabel("Back  Close")
                }
            }
        }
    }

    @Composable
    private fun FooterLabel(text: String) {
        Text(text, fontSize = 12.sp, color = Color(0xFFD5E4ED),
            fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
