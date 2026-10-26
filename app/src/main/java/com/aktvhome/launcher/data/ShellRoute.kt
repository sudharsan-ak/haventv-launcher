package com.aktvhome.launcher.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * How Haven reaches this TV's shell: root first, then this TV's own debugging (ADB on port 5555,
 * after you allow Haven once on screen). Shared by the one-time setup and Cleanup; each runs only
 * its own fixed commands through it.
 */
class ShellRoute(private val context: Context) {

    enum class Kind { ROOT, ADB }

    /** An open shell. [run] returns what a command printed, ending "exit=N" when it failed. */
    class Shell internal constructor(val kind: Kind, private val adb: AdbConnection?) {

        fun run(command: String): String = adb?.shell("$command; echo exit=\$?") ?: Root.run(command)

        fun close() {
            adb?.close()
        }
    }

    sealed interface Opened {
        class Ready(val shell: Shell) : Opened

        /** Neither route worked: [message] says why; [wirelessOnly] when the TV only offers Wireless debugging. */
        class Unreachable(val message: String, val wirelessOnly: Boolean) : Opened
    }

    /**
     * Tries root, then this TV's debugging. [progress] gets what's happening for the screen, [log]
     * each step, and [onAllowPrompt] runs (on a background thread) while the TV asks you to allow Haven.
     */
    suspend fun open(progress: (String) -> Unit, log: (String) -> Unit, onAllowPrompt: () -> Unit): Opened {
        progress("Checking for root access…")
        if (io { Root.available() }) {
            log("Root access found.")
            return Opened.Ready(Shell(Kind.ROOT, null))
        }
        log("No root access.")

        progress("Connecting to this TV's debugging…")
        return try {
            val adb = io {
                AdbConnection.connect(LOCALHOST, ADB_PORT, AdbKey.loadOrCreate(File(context.filesDir, "adb")), keyName(), onAllowPrompt = onAllowPrompt)
            }
            log("Connected to this TV's debugging.")
            Opened.Ready(Shell(Kind.ADB, adb))
        } catch (e: ConnectException) {
            log("Debugging is off (nothing on port $ADB_PORT).")
            Opened.Unreachable("Haven couldn't reach this TV's debugging.", wirelessDebuggingOn())
        } catch (e: Exception) {
            log("Debugging: ${e.message ?: e.javaClass.simpleName}")
            Opened.Unreachable(e.message ?: "Haven couldn't reach this TV's debugging.", e is AdbTlsRequired)
        }
    }

    /**
     * Which route Cleanup can expect, found without asking anything on screen: whether debugging
     * is listening, whether su is there, and whether Wireless debugging is on.
     */
    suspend fun probe(): CleanupRoute = io { CleanupPlan.route(portOpen(), suPresent(), wirelessDebuggingOn()) }

    private fun portOpen(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(LOCALHOST, ADB_PORT), 1_000) }
        true
    }.getOrDefault(false)

    private fun suPresent(): Boolean {
        val path = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
        return (path + SU_DIRS).distinct().any { File(it, "su").exists() }
    }

    private fun wirelessDebuggingOn(): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)

    private fun keyName() = "haven@" + Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val ADB_PORT = 5555
        val SU_DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/debug_ramdisk")
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
