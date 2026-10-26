package com.aktvhome.launcher.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Cleanup's Disable and Restore, through [ShellRoute]. Before switching anything off it saves the
 * package's state in a record (kept in the backup file, and never cleared by Reset), checks the
 * guards again and runs only [CleanupPlan]'s fixed commands. Whether a change worked is read back
 * from Android, not taken from the command's output. Every step goes in a short log.
 */
class CleanupRunner(private val context: Context, private val packages: PackageScan, private val scope: CoroutineScope) {

    enum class Phase {
        IDLE,
        WORKING,

        /** The TV is showing "Allow debugging?" and waiting for you. */
        ALLOW,
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val logPrefs = context.getSharedPreferences("cleanup_log", Context.MODE_PRIVATE)
    private val shellRoute = ShellRoute(context)

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** What's happening now, or how the last run ended. */
    var message by mutableStateOf("")
        private set

    /** How Disable and Restore can reach the shell; null until [probe] has looked. */
    var route by mutableStateOf<CleanupRoute?>(null)
        private set

    /** Packages Haven switched off, by name, with their state from before. */
    var record by mutableStateOf(readRecord())
        private set

    /** Recorded packages that are on again (after a firmware update, or switched on by hand). */
    var cameBack by mutableStateOf<List<CleanupEntry>>(emptyList())
        private set

    /** Offer, once per firmware, to switch off again what came back after an update. */
    var offerUpdate by mutableStateOf(false)
        private set

    /** The Cleanup log, oldest first. */
    val logLines = mutableStateListOf<String>().apply {
        addAll(logPrefs.getString("log", "").orEmpty().lines().filter { it.isNotBlank() })
    }

    val busy get() = phase != Phase.IDLE

    /** After a backup restore: the record may have gained entries. */
    fun reload() {
        record = readRecord()
        check()
    }

    suspend fun probe() {
        val found = shellRoute.probe()
        // A run that met Wireless debugging's TLS knows better than the probe.
        if (route != CleanupRoute.WIRELESS_ONLY || found != CleanupRoute.DEBUGGING_OFF) route = found
    }

    /** Looks at the recorded packages only (cheap): which are on again, and whether to offer. */
    fun check() {
        val current = record.keys.associateWith { packages.state(it) }
        cameBack = CleanupPlan.cameBack(record.values, current)
        offerUpdate = CleanupPlan.offerAfterUpdate(cameBack, Build.FINGERPRINT, prefs.getString(OFFERED, null))
    }

    fun dismissUpdateOffer() {
        prefs.edit().putString(OFFERED, Build.FINGERPRINT).apply()
        offerUpdate = false
    }

    fun disable(pkgs: Collection<String>) {
        if (busy || pkgs.isEmpty()) return
        phase = Phase.WORKING
        scope.launch { runDisable(pkgs.distinct()) }
    }

    fun restore(pkgs: Collection<String>) {
        if (busy || pkgs.isEmpty()) return
        phase = Phase.WORKING
        scope.launch { runRestore(pkgs.distinct()) }
    }

    fun restoreAll() = restore(record.keys)

    /** Switches off again what came back after an update (the guards are checked again first). */
    fun disableAgain() {
        dismissUpdateOffer()
        disable(cameBack.map { it.pkg })
    }

    private suspend fun runDisable(pkgs: List<String>) {
        message = "Checking…"
        val guard = io { packages.guardContext() }
        val fingerprint = Build.FINGERPRINT
        val plan = mutableListOf<String>()
        val added = mutableSetOf<String>()
        for (p in pkgs) {
            if (!CleanupPlan.validName(p)) {
                log("Skipped a package with an unexpected name.")
                continue
            }
            val f = io { packages.facts(p) }
            val reason = f?.let { CleanupPlan.blocked(it, guard) }
            when {
                f == null -> log("$p: not installed.")
                reason != null -> log("$p: never switched off (${reason.label}).")
                Cleanup.classify(f).category !in CleanupPlan.DISABLE_CATEGORIES -> log("$p: not something Cleanup switches off.")
                !f.state.on -> log("$p: already off.")
                else -> {
                    // Saved before the change, so Restore can always put it back.
                    val entry = CleanupPlan.entryFor(f, record[p], System.currentTimeMillis(), fingerprint)!!
                    if (p !in record) added += p
                    save(entry)
                    plan += p
                }
            }
        }
        if (plan.isEmpty()) return done("Nothing to switch off.")
        log("Switching off ${plan.size}: ${plan.joinToString()}.")

        val shell = openShell() ?: run {
            added.forEach(::forget)
            return done(message)
        }
        try {
            plan.forEach { p ->
                val out = io { shell.run(CleanupPlan.disableCommand(p)) }
                if (io { packages.state(p) }?.on == false) log("$p: off.") else log("$p: still on.${output(out)}")
            }
        } catch (e: Exception) {
            log("The connection dropped: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            io { shell.close() }
        }
        // Whatever didn't change has nothing to restore.
        val stillOn = plan.filter { io { packages.state(it) }?.on != false }
        stillOn.filter { it in added }.forEach(::forget)
        done(
            if (stillOn.isEmpty()) "Switched off ${plan.size}."
            else "Switched off ${plan.size - stillOn.size} of ${plan.size}. The log says why the rest stayed on.",
        )
    }

    private suspend fun runRestore(pkgs: List<String>) {
        message = "Checking…"
        val plan = mutableListOf<CleanupEntry>()
        for (p in pkgs) {
            val entry = record[p] ?: continue
            val now = io { packages.state(p) }
            when {
                now == null -> {
                    log("$p: no longer installed; dropped from the record.")
                    forget(p)
                }
                now.on -> {
                    log("$p: already on.")
                    forget(p)
                }
                else -> plan += entry
            }
        }
        if (plan.isEmpty()) return done("Nothing to restore.")
        log("Restoring ${plan.size}: ${plan.joinToString { it.pkg }}.")

        val shell = openShell() ?: return done(message)
        var restored = 0
        try {
            plan.forEach { e ->
                var out = ""
                for (c in CleanupPlan.restoreCommands(e.pkg, e.prior)) {
                    out = io { shell.run(c) }
                    if (io { packages.state(e.pkg) }?.on == true) break
                }
                if (io { packages.state(e.pkg) }?.on == true) {
                    log("${e.pkg}: back on.")
                    forget(e.pkg)
                    restored++
                } else {
                    log("${e.pkg}: still off.${output(out)}")
                }
            }
        } catch (ex: Exception) {
            log("The connection dropped: ${ex.message ?: ex.javaClass.simpleName}")
        } finally {
            io { shell.close() }
        }
        done(if (restored == plan.size) "Restored ${plan.size}." else "Restored $restored of ${plan.size}. The log says why.")
    }

    /** Opens the shell, or says why it can't and returns null. */
    private suspend fun openShell(): ShellRoute.Shell? {
        val opened = shellRoute.open(
            progress = { message = it },
            log = ::log,
            onAllowPrompt = {
                scope.launch {
                    phase = Phase.ALLOW
                    message = "Allow Haven in the box on screen."
                    log("Asked the TV to allow Haven.")
                }
            },
        )
        return when (opened) {
            is ShellRoute.Opened.Ready -> {
                phase = Phase.WORKING
                message = "Working…"
                route = if (opened.shell.kind == ShellRoute.Kind.ROOT) CleanupRoute.ROOT else CleanupRoute.READY
                opened.shell
            }
            is ShellRoute.Opened.Unreachable -> {
                route = if (opened.wirelessOnly) CleanupRoute.WIRELESS_ONLY else CleanupRoute.DEBUGGING_OFF
                message = if (opened.wirelessOnly) "This TV only offers Wireless debugging, which Haven can't use yet." else opened.message
                null
            }
        }
    }

    private suspend fun done(summary: String) {
        packages.refresh()
        check()
        message = summary
        phase = Phase.IDLE
    }

    private fun save(entry: CleanupEntry) {
        // commit, not apply: the record has to be on disk before the package changes.
        prefs.edit().putString(KEY + entry.pkg, entry.encode()).commit()
        record = record + (entry.pkg to entry)
    }

    private fun forget(pkg: String) {
        prefs.edit().remove(KEY + pkg).commit()
        record = record - pkg
    }

    private fun readRecord(): Map<String, CleanupEntry> =
        prefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith(KEY) || v !is String) null else CleanupEntry.decode(k.removePrefix(KEY), v)
        }.associateBy { it.pkg }

    private fun log(line: String) {
        logLines += "${LocalTime.now().format(TIME)}  $line"
        while (logLines.size > MAX_LOG) logLines.removeAt(0)
        logPrefs.edit().putString("log", logLines.joinToString("\n")).apply()
    }

    /** A command's output for the log, on one line; blank when it printed nothing. */
    private fun output(out: String): String = out.trim().lines().joinToString(" ").trim().let { if (it.isEmpty()) "" else " $it" }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** The record's preferences; the backup file keeps them, and Reset leaves them alone. */
        const val PREFS = "cleanup"
        private const val KEY = "pkg:"
        private const val OFFERED = "offeredFingerprint"
        private const val MAX_LOG = 80
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
