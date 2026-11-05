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
import kotlinx.coroutines.launch
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
                val bytes = client.snapshot(task.taskId)
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                view = view.copy(
                    busy = false, snapshot = bitmap, snapshotFor = task.taskId,
                    message = if (bitmap != null) "Task snapshot available for " + task.pkg
                        else "No real task snapshot for " + task.pkg + " (secure/unsupported/not cached)",
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
            Text("COMMIT 1 · ANDROID 12 DIAGNOSTICS · NOT FINAL UI", color = Color(0xFF92AEB6), fontSize = 13.sp)
            Text(data.shizuku, color = Color(0xFF8CE6E8))
            Text(data.message, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConnect, enabled = !data.busy) { Text("Connect / Refresh tasks") }
                OutlinedButton(onClick = onGrant, enabled = !data.busy) { Text("Grant Shizuku") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            Text("For one-shot Accessibility captures: choose Capture & open, use the app, then reopen this lab through ADB.", color = Color(0xFF9CB2BA), fontSize = 12.sp)
            if (data.rawStart.isNotBlank()) Text("Shell output: " + data.rawStart, color = Color(0xFF8C9EA4), fontSize = 11.sp)
            data.tasks.forEach { task ->
                Column(
                    Modifier.fillMaxWidth().border(1.dp, outline, RoundedCornerShape(12.dp))
                        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(labelFor(task.pkg) + "  ·  task #" + task.taskId, fontWeight = FontWeight.Bold)
                    Text(task.pkg, color = Color(0xFF9DB1B9), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onSnapshot(task) }, enabled = !data.busy) { Text("Snapshot") }
                        OutlinedButton(onClick = { onCapture(task) }, enabled = !data.busy) { Text("Capture & open") }
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
                Text("Saved Android task snapshot · task #" + data.snapshotFor, fontWeight = FontWeight.Bold)
                if (data.snapshot != null) {
                    Image(data.snapshot.asImageBitmap(), "Android task snapshot", Modifier.width(480.dp).height(270.dp))
                } else Text("No usable saved snapshot available")
            }
            Text("Accessibility capture result: " + data.captureStatus, color = Color(0xFF8CE6E8))
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
