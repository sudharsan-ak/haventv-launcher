package com.aktvhome.launcher.recent

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private data class LabState(
    val shizuku: String = "Checking Shizuku",
    val message: String = "Connect to inspect recent Android tasks",
    val tasks: List<RecentTask> = emptyList(),
    val rawStart: String = "",
    val snapshot: Bitmap? = null,
    val snapshotFor: Int? = null,
    val capture: Bitmap? = null,
    val captureStatus: String = "No accessibility capture test yet",
    val automatic: Boolean = false,
    val cachedPreview: Bitmap? = null,
    val cachedPackage: String? = null,
    val cachedLabel: String = "",
    val confirmDismiss: Int? = null,
    val busy: Boolean = false,
)

/**
 * Commit 1 only: a diagnostic screen, not the finished app switcher.
 * Start it from ADB; it does not change Home, Kingdom, Tide, or remote shortcuts.
 *
 * adb shell am start -n com.aktvhome.launcher/.recent.RecentAppsLabActivity
 */
class RecentAppsLabActivity : ComponentActivity() {
    private val client by lazy { RecentProbeClient(this) }
    private var view by mutableStateOf(LabState())

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == 6214) runOnUiThread {
            view = view.copy(shizuku = client.status())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF8CE6E8), surface = Color(0xFF19282E),
                onSurface = Color(0xFFF2F7F8),
            )) {
                LabScreen(
                    data = view,
                    onConnect = ::connectAndScan,
                    onGrant = {
                        runCatching { client.requestPermission() }
                            .onFailure { problem("Cannot request Shizuku: " + it.message) }
                    },
                    onSnapshot = ::testSnapshot,
                    onRestore = ::testRestore,
                    onDismiss = ::testDismiss,
                    onCapture = ::captureApp,
                    onCached = ::showCached,
                    onToggleAutomatic = ::toggleAutomatic,
                    onClearCache = ::clearCached,
                    onClearConfirmation = { view = view.copy(confirmDismiss = null) },
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val file = RecentCapture.imageFile(this)
        view = view.copy(
            shizuku = client.status(),
            captureStatus = RecentCapture.status(this),
            automatic = RecentPreviewStore.enabled(this),
            capture = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null,
        )
    }

    private fun problem(text: String) {
        view = view.copy(message = text, busy = false)
    }

    private fun connectAndScan() {
        if (view.busy) return
        view = view.copy(busy = true, message = "Connecting to Shizuku UserService...")
        lifecycleScope.launch {
            try {
                if (!client.connect()) {
                    problem("Shizuku not ready or UserService connection timed out. Check permission.")
                    return@launch
                }
                val raw = client.list()
                val tasks = RecentTaskParser.parse(raw, packageName)
                view = view.copy(
                    busy = false, shizuku = client.status(), tasks = tasks,
                    rawStart = raw.take(280).replace('\n', ' '),
                    message = if (tasks.isEmpty()) "No tasks parsed. Check the raw diagnostic below."
                        else "Found " + tasks.size + " recent apps. Select an action to test.",
                )
            } catch (e: Exception) {
                problem("Task query failed: " + e.javaClass.simpleName + ": " + e.message)
            }
        }
    }

    private fun testSnapshot(task: RecentTask) {
        if (view.busy) return
        view = view.copy(busy = true, message = "Reading Android snapshot for " + task.pkg)
        lifecycleScope.launch {
            try {
                val probe = client.snapshot(task.taskId)
                val bytes = probe.bytes
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                view = view.copy(
                    busy = false, snapshot = bitmap, snapshotFor = task.taskId,
                    message = "Native snapshot for " + task.pkg + ": " + probe.detail,
                )
            } catch (e: Exception) {
                problem("Snapshot failed: " + e.javaClass.simpleName + ": " + e.message)
            }
        }
    }

    private fun testRestore(task: RecentTask) {
        if (view.busy) return
        view = view.copy(busy = true)
        lifecycleScope.launch {
            try {
                if (client.restore(task.taskId)) {
                    // Give Android time to bring the existing task forward before closing the lab.
                    delay(180)
                    finish()
                } else {
                    problem("Task resume failed for " + task.pkg + " (no relaunch fallback in test mode)")
                }
            } catch (e: Exception) {
                problem("Task resume error: " + e.message)
            }
        }
    }

    private fun testDismiss(task: RecentTask) {
        if (view.confirmDismiss != task.taskId) {
            view = view.copy(confirmDismiss = task.taskId, message = "Select Remove again to confirm. No force-stop.")
            return
        }
        if (view.busy) return
        view = view.copy(busy = true, confirmDismiss = null)
        lifecycleScope.launch {
            try {
                val removed = client.dismiss(task.taskId)
                val raw = client.list()
                view = view.copy(
                    busy = false, tasks = RecentTaskParser.parse(raw, packageName),
                    message = if (removed) "Removed task " + task.taskId + " (no force-stop)"
                        else "Android refused task removal for " + task.taskId,
                )
            } catch (e: Exception) {
                problem("Remove failed: " + e.message)
            }
        }
    }

    /** Captures once after this app reaches foreground; then use ADB to reopen the lab. */
    private fun captureApp(task: RecentTask) {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(task.pkg)
            ?: packageManager.getLaunchIntentForPackage(task.pkg)
        if (launch == null) {
            problem("No launch intent for " + task.pkg)
            return
        }
        RecentCapture.arm(this, task.pkg)
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { problem("Cannot open " + task.pkg + ": " + it.message) }
    }

    private fun toggleAutomatic() {
        val enabled = !RecentPreviewStore.enabled(this)
        RecentPreviewStore.setEnabled(this, enabled)
        RecentCapture.resetAuto()
        if (!enabled) RecentMediaCollector.stop()
        view = view.copy(automatic = enabled,
            message = if (enabled) "Automatic previews ON. Open another app, then return to this lab."
                else "Automatic previews OFF. Existing local previews are retained.")
    }

    private fun clearCached() {
        RecentPreviewStore.clearAll(this)
        RecentMediaStore.clearAll(this)
        view = view.copy(cachedPreview = null, cachedPackage = null,
            message = "All saved Recent Apps previews cleared.")
    }

    private fun showCached(task: RecentTask) {
        view = view.copy(cachedPackage = task.pkg, cachedPreview = null,
            cachedLabel = "Checking preview", message = "Inspecting " + task.pkg)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val file = RecentPreviewStore.file(applicationContext, task.pkg)
                val screenshot = if (file.exists())
                    BitmapFactory.decodeFile(file.absolutePath) else null
                val partial = screenshot != null &&
                    RecentPreviewStore.isPartialDark(applicationContext, task.pkg)
                // Dark backgrounds are normal for TV apps. This flag is diagnostic
                // only, not a reason to replace an actual usable screenshot.
                // Avoid querying media sessions/Watch Next when a screenshot exists.
                val media = if (screenshot == null)
                    RecentMediaArtwork.find(applicationContext, task.pkg) else null
                val choice = RecentPreviewSelection.choose(
                    screenshotAvailable = screenshot != null,
                    mediaAvailable = media?.bitmap != null,
                )
                val bitmap = when (choice) {
                    RecentPreviewChoice.SCREENSHOT -> screenshot
                    RecentPreviewChoice.MEDIA -> media?.bitmap
                    RecentPreviewChoice.APP_ARTWORK ->
                        RecentMediaArtwork.appFallback(applicationContext, task.pkg)
                }
                val label = when (choice) {
                    RecentPreviewChoice.SCREENSHOT -> if (partial)
                        "Saved screenshot (dark UI; screenshot preferred)"
                        else "Saved screenshot"
                    RecentPreviewChoice.MEDIA -> media?.label ?: "Media artwork fallback"
                    RecentPreviewChoice.APP_ARTWORK -> "App artwork fallback"
                }
                Triple(bitmap, label, media?.detail.orEmpty())
            }
            // The diagnostic screen only displays results for the latest selected app.
            if (view.cachedPackage != task.pkg) return@launch
            val details = RecentPreviewStore.description(this@RecentAppsLabActivity, task.pkg)
            view = view.copy(cachedPreview = result.first,
                cachedLabel = result.second,
                message = details + if (result.third.isBlank()) "" else " | " + result.third)
        }
    }

    private fun labelFor(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    @Composable
    private fun LabScreen(
        data: LabState,
        onConnect: () -> Unit,
        onGrant: () -> Unit,
        onSnapshot: (RecentTask) -> Unit,
        onRestore: (RecentTask) -> Unit,
        onDismiss: (RecentTask) -> Unit,
        onCapture: (RecentTask) -> Unit,
        onCached: (RecentTask) -> Unit,
        onToggleAutomatic: () -> Unit,
        onClearCache: () -> Unit,
        onClearConfirmation: () -> Unit,
        onClose: () -> Unit,
    ) {
        val outline = Color(0xFF48646B)
        Column(
            Modifier.fillMaxSize().background(Color(0xFF101C22))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 54.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Haven · Recent Apps Lab", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("COMMIT 5 · SCREENSHOT PRIORITY FIX · NOT FINAL UI", color = Color(0xFF92AEB6), fontSize = 13.sp)
            Text(data.shizuku, color = Color(0xFF8CE6E8))
            Text(data.message, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConnect, enabled = !data.busy) { Text("Connect / Refresh tasks") }
                OutlinedButton(onClick = onGrant, enabled = !data.busy) { Text("Grant Shizuku") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onToggleAutomatic) {
                    Text(if (data.automatic) "Automatic previews: ON" else "Automatic previews: OFF")
                }
                OutlinedButton(onClick = onClearCache) { Text("Clear previews") }
            }
            Text("Opt-in previews: on-device screenshots plus optional foreground media metadata. " +
                "Watch Next suggestions are labeled and may not match current playback.",
                color = Color(0xFF9CB2BA), fontSize = 12.sp)
            Text("Cached previews: " + RecentPreviewStore.count(this@RecentAppsLabActivity) +
                " / " + RecentPreviewPolicy.MAX_CACHED_APPS,
                color = Color(0xFF8CE6E8), fontSize = 12.sp)
            Text("Manual one-shot test: Capture & open, then reopen via ADB.",
                color = Color(0xFF9CB2BA), fontSize = 12.sp)
            if (data.rawStart.isNotBlank()) Text("Shell output: " + data.rawStart, color = Color(0xFF8C9EA4), fontSize = 11.sp)
            data.tasks.forEach { task ->
                Column(
                    Modifier.fillMaxWidth().border(1.dp, outline, RoundedCornerShape(12.dp))
                        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(labelFor(task.pkg) + "  ·  task #" + task.taskId, fontWeight = FontWeight.Bold)
                    Text(task.pkg, color = Color(0xFF9DB1B9), fontSize = 12.sp)
                    Text(RecentPreviewStore.description(this@RecentAppsLabActivity, task.pkg),
                        color = Color(0xFF9DB1B9), fontSize = 12.sp)
                    Text(RecentMediaStore.status(this@RecentAppsLabActivity, task.pkg),
                        color = Color(0xFF9DB1B9), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onSnapshot(task) }, enabled = !data.busy) { Text("Snapshot") }
                        OutlinedButton(onClick = { onCapture(task) }, enabled = !data.busy) { Text("Capture & open") }
                        OutlinedButton(onClick = { onCached(task) }) { Text("Cached preview") }
                        OutlinedButton(onClick = { onRestore(task) }, enabled = !data.busy) { Text("Resume task") }
                        OutlinedButton(onClick = { onDismiss(task) }, enabled = !data.busy) {
                            Text(if (data.confirmDismiss == task.taskId) "Confirm remove" else "Remove")
                        }
                    }
                }
            }
            if (data.confirmDismiss != null) {
                OutlinedButton(onClick = onClearConfirmation) { Text("Cancel remove") }
            }
            if (data.snapshotFor != null) {
                Text("Saved Android task snapshot · task #" + data.snapshotFor,
                    fontWeight = FontWeight.Bold, color = Color.White)
                if (data.snapshot != null) {
                    Image(data.snapshot.asImageBitmap(), "Android task snapshot", Modifier.width(480.dp).height(270.dp))
                } else Text("No usable saved snapshot available", color = Color.White)
            }
            if (data.cachedPackage != null) {
                Text(data.cachedLabel + " · " + data.cachedPackage,
                    fontWeight = FontWeight.Bold, color = Color.White)
                if (data.cachedPreview != null) {
                    Image(data.cachedPreview.asImageBitmap(), "Cached screenshot or app artwork",
                        Modifier.width(480.dp).height(270.dp))
                } else Text("No saved screenshot or app artwork available", color = Color.White)
            }
            Text("Accessibility manual test: " + data.captureStatus, color = Color(0xFF8CE6E8))
            if (data.capture != null) {
                Image(data.capture.asImageBitmap(), "One-shot Accessibility capture", Modifier.width(480.dp).height(270.dp))
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        client.close()
        super.onDestroy()
    }
}
