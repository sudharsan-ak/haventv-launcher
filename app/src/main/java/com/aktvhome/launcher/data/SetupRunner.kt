package com.aktvhome.launcher.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The one-time setup, from the TV itself. It tries, in order: nothing to do (already granted),
 * root, then this TV's own debugging (ADB on port 5555, after you allow Haven once on screen). It
 * runs only [Setup]'s fixed commands, then turns on the Home button service itself. Every step goes
 * in a short log kept between runs.
 */
class SetupRunner(private val context: Context, private val device: DeviceCapabilities, private val scope: CoroutineScope) {

    enum class Phase {
        IDLE,
        WORKING,

        /** The TV is showing "Allow debugging?" and waiting for you. */
        ALLOW,

        /** No root and debugging is off (or refused): show how to turn it on, or a computer's commands. */
        NEEDS_DEBUGGING,
        DONE,
    }

    enum class Route { ALREADY, ROOT, ADB }

    private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** What's happening now, or why it stopped. */
    var message by mutableStateOf("")
        private set

    /** How the last run got its grants, once it's done. */
    var route by mutableStateOf<Route?>(null)
        private set

    /** The setup log, oldest first. */
    val logLines = mutableStateListOf<String>().apply {
        addAll(prefs.getString("log", "").orEmpty().lines().filter { it.isNotBlank() })
    }

    /** The first-run offer was answered (Start or Later); it isn't shown again. */
    var offered by mutableStateOf(prefs.getBoolean("offered", false))
        private set

    fun markOffered() {
        offered = true
        prefs.edit().putBoolean("offered", true).apply()
    }

    val busy get() = phase == Phase.WORKING || phase == Phase.ALLOW

    fun start() {
        if (busy) return
        scope.launch { run() }
    }

    /** Back to the start screen (after Close), unless a run is still going. */
    fun reset() {
        if (!busy) {
            phase = Phase.IDLE
            message = ""
        }
    }

    private suspend fun run() {
        phase = Phase.WORKING
        route = null
        device.refresh()
        val needed = Setup.needed(device.status)
        log("Setup started. Needs: ${needed.joinToString { it.label }.ifEmpty { "nothing" }}.")
        val commands = Setup.commands(context.packageName, needed, Build.VERSION.SDK_INT)
        if (commands.isEmpty()) {
            route = Route.ALREADY
            return finish()
        }

        message = "Checking for root access…"
        if (io { Root.available() }) {
            log("Root access found.")
            route = Route.ROOT
            commands.forEach { c -> log("$ $c${result(io { Root.run(c) })}") }
            return finish()
        }
        log("No root access.")

        message = "Connecting to this TV's debugging…"
        val adb = try {
            io {
                AdbConnection.connect(LOCALHOST, ADB_PORT, AdbKey.loadOrCreate(File(context.filesDir, "adb")), keyName()) {
                    scope.launch {
                        phase = Phase.ALLOW
                        message = "Allow Haven in the box on screen."
                        log("Asked the TV to allow Haven.")
                    }
                }
            }
        } catch (e: ConnectException) {
            log("Debugging is off (nothing on port $ADB_PORT).")
            message = "Haven couldn't reach this TV's debugging."
            phase = Phase.NEEDS_DEBUGGING
            return
        } catch (e: Exception) {
            log("Debugging: ${e.message ?: e.javaClass.simpleName}")
            message = e.message ?: "Haven couldn't reach this TV's debugging."
            phase = Phase.NEEDS_DEBUGGING
            return
        }
        phase = Phase.WORKING
        message = "Setting up…"
        route = Route.ADB
        log("Connected to this TV's debugging.")
        try {
            commands.forEach { c -> log("$ $c${result(io { adb.shell("$c; echo exit=\$?") })}") }
        } catch (e: Exception) {
            log("The connection dropped: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            io { adb.close() }
        }
        finish()
    }

    /** Turns on the Home button service once Haven may, then checks what's left. */
    private suspend fun finish() {
        message = "Checking…"
        device.refresh()
        if (device.state(Capability.HOME_SERVICE) == CapState.NEEDS_SETUP && PrivateDns.canWrite(context)) {
            log(if (HomeOverride.set(context, true)) "Turned on the Home button service." else "Couldn't turn on the Home button service.")
            // Android binds the service a moment later.
            delay(1_500)
            device.refresh()
        }
        val left = Setup.needed(device.status)
        log(if (left.isEmpty()) "Done: everything is set up." else "Done. Still needs: ${left.joinToString { it.label }}.")
        message = ""
        phase = Phase.DONE
    }

    private fun log(line: String) {
        logLines += "${LocalTime.now().format(TIME)}  $line"
        while (logLines.size > MAX_LOG) logLines.removeAt(0)
        prefs.edit().putString("log", logLines.joinToString("\n")).apply()
    }

    /** A command's output for the log: blank when it printed nothing but a clean exit. */
    private fun result(out: String): String {
        val text = out.trim().removeSuffix("exit=0").trim()
        return if (text.isEmpty()) "  (ok)" else "\n    " + text.lines().joinToString("\n    ")
    }

    private fun keyName() = "haven@" + Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val ADB_PORT = 5555
        const val MAX_LOG = 80
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/** Root through su, if the device has it and you allow Haven when it asks. */
private object Root {

    fun available(): Boolean = run("id").contains("uid=0")

    /** Runs [command] as root; empty when there's no su or it isn't allowed in time. */
    fun run(command: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        // A root manager may ask you first.
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroy()
            return ""
        }
        p.inputStream.bufferedReader().readText() + if (p.exitValue() == 0) "" else "exit=${p.exitValue()}"
    }.getOrDefault("")
}
