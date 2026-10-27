package com.aktvhome.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.aktvhome.launcher.core.guard.Answer
import com.aktvhome.launcher.core.guard.GuardRules
import com.aktvhome.launcher.core.guard.GuardState
import com.aktvhome.launcher.core.guard.ServiceStatus

/** An accessibility service on this TV, as the Accessibility tab lists it. */
data class A11yService(
    /** "pkg/pkg.Service", the form Haven compares and saves. */
    val id: String,
    val label: String,
    val app: String,
    val status: ServiceStatus,
    /** Haven's own Home button service, kept by the Home button tab instead. */
    val self: Boolean,
)

/**
 * The Accessibility Watchdog on the device: reads and writes the TV's list of enabled
 * accessibility services and saves the toggles. The decisions are in [GuardRules] (Java, in
 * haven-core).
 *
 * Apps on a USB drive used as storage load after Android checks accessibility services at boot,
 * so Android removes their services from the enabled list. Haven runs from internal storage, so it
 * puts back every service you keep on once its app loads. Until the TV has settled after a restart
 * (every kept service's app loaded and on, or 10 minutes), a missing service counts as dropped by
 * the boot; after that, one switched off outside Haven is asked about rather than guessed at.
 *
 * Checks, toggles and answers take turns ([lock]): the checks run in the background and the
 * toggles on the main thread, and a check landing in the middle of a toggle could undo it.
 */
object AccessibilityGuard {

    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private val lock = Any()

    /** Bumped on every change, so the Accessibility tab redraws. */
    var version by mutableIntStateOf(0)
        private set

    /**
     * Run at boot, every so often, and whenever the TV's list changes. Switches dropped services
     * back on and returns the ones to ask about (switched off outside Haven), each once.
     */
    fun check(context: Context): List<String> {
        synchronized(lock) {
            if (!PrivateDns.canWrite(context)) return emptyList()
            val prefs = prefs(context)
            val current = read(context)
            val r = GuardRules.decide(load(context, prefs), current, { available(context, it) }, booting(context, prefs), context.packageName)
            if (r.restore.isNotEmpty()) write(context, current + r.restore)
            save(context, prefs, r.state)
            return r.ask
        }
    }

    /** Your answer about [service], from the question or the reminder after a restart. */
    fun answer(context: Context, service: String, answer: Answer) {
        synchronized(lock) {
            val prefs = prefs(context)
            val id = GuardRules.normal(service)
            val state = GuardRules.answer(load(context, prefs), id, answer)
            if (answer == Answer.TURN_ON && PrivateDns.canWrite(context)) write(context, read(context) + id)
            save(context, prefs, state)
        }
    }

    /** Services to remind you about after a restart, once the TV has settled. */
    fun reminders(context: Context): List<String> {
        synchronized(lock) {
            val prefs = prefs(context)
            if (!PrivateDns.canWrite(context) || booting(context, prefs)) return emptyList()
            return GuardRules.reminders(load(context, prefs), read(context), { available(context, it) }, context.packageName)
        }
    }

    /** The reminder about [service] was shown and went unanswered: ask again after the next restart. */
    fun reminded(context: Context, service: String) {
        synchronized(lock) {
            val prefs = prefs(context)
            save(context, prefs, GuardRules.reminded(load(context, prefs), service))
        }
    }

    /** A service's name as the TV shows it, or its app's. */
    fun label(context: Context, service: String): String {
        val pm = context.packageManager
        val cn = ComponentName.unflattenFromString(service) ?: return service
        return runCatching { pm.getServiceInfo(cn, 0).loadLabel(pm).toString() }.getOrNull()
            ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(cn.packageName, 0)).toString() }.getOrDefault(cn.packageName)
    }

    /** The Accessibility tab's toggle. Returns false without the one-time setup's grant. */
    fun setKeep(context: Context, service: String, on: Boolean): Boolean {
        synchronized(lock) {
            if (!PrivateDns.canWrite(context)) return false
            val prefs = prefs(context)
            val id = GuardRules.normal(service)
            val state = GuardRules.setKeep(load(context, prefs), id, on)
            val current = read(context)
            write(context, if (on) current + id else current - id)
            save(context, prefs, state)
            return true
        }
    }

    /** Called at boot: until the drive's apps are back, missing services were dropped by the boot. */
    fun onBoot(context: Context) {
        prefs(context).edit().putBoolean("bootPending", true).putLong("bootAt", System.currentTimeMillis()).apply()
    }

    /** Ends the post-boot window once every kept service's app is loaded and on (or after 10 minutes). */
    fun settle(context: Context) {
        synchronized(lock) {
            val prefs = prefs(context)
            val boot = bootCount(context)
            if (!prefs.getBoolean("bootPending", false) && prefs.getInt("settledBoot", -1) == boot) return
            val done = GuardRules.settled(load(context, prefs), read(context), { available(context, it) }, context.packageName)
            val expired = System.currentTimeMillis() - prefs.getLong("bootAt", 0L) > GuardRules.BOOT_WINDOW_MS
            if (done || expired) prefs.edit().putBoolean("bootPending", false).putInt("settledBoot", boot).apply()
        }
    }

    /**
     * Every accessibility service on this TV, plus kept ones whose app isn't loaded right now (a
     * USB drive), with their state. Haven's own service first, then by name.
     */
    fun services(context: Context): List<A11yService> = synchronized(lock) {
        val pm = context.packageManager
        val state = load(context, prefs(context))
        val current = read(context)
        val installed = context.getSystemService(AccessibilityManager::class.java)?.installedAccessibilityServiceList.orEmpty()
            .mapNotNull { it.resolveInfo?.serviceInfo }
            .map { si ->
                val id = GuardRules.normal("${si.packageName}/${si.name}")
                val app = runCatching { si.applicationInfo.loadLabel(pm).toString() }.getOrDefault(si.packageName)
                val label = runCatching { si.loadLabel(pm).toString() }.getOrDefault(app)
                A11yService(id, label, app, GuardRules.status(state, id, current, true), GuardRules.isSelf(id, context.packageName))
            }
        val seen = installed.map { it.id }.toSet()
        val missing = state.keep.keys.filter { it !in seen && !GuardRules.isSelf(it, context.packageName) }.map { id ->
            val pkg = id.substringBefore('/')
            A11yService(id, pkg, pkg, GuardRules.status(state, id, current, false), false)
        }
        (installed + missing).sortedWith(compareBy({ !it.self }, { it.label.lowercase() }))
    }

    /** Another watchdog app is installed; two of them can undo each other's work. */
    fun otherWatchdog(context: Context): String? = OTHER_WATCHDOGS.firstNotNullOfOrNull { pkg ->
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("a11y_guard", Context.MODE_PRIVATE)

    /**
     * The saved state. On first run it comes from the list earlier versions kept ("wanted"), or
     * from what's on now; after a restart, [GuardRules.newBoot] starts the new boot.
     */
    private fun load(context: Context, prefs: SharedPreferences): GuardState {
        val state = if (prefs.contains("keep")) {
            GuardState(
                GuardState.decodeKeep(prefs.getString("keep", null)),
                GuardState.decodeSet(prefs.getString("dropped", null)),
                GuardState.decodeSet(prefs.getString("asking", null)),
                GuardState.decodeSet(prefs.getString("reminded", null)),
            )
        } else {
            GuardRules.start(prefs.getString("wanted", null)?.let { GuardState.decodeSet(it) }, read(context), context.packageName)
        }
        return if (prefs.getInt("stateBoot", -1) == bootCount(context)) state else GuardRules.newBoot(state)
    }

    private fun save(context: Context, prefs: SharedPreferences, state: GuardState) {
        prefs.edit()
            .putString("keep", state.encodeKeep())
            .putString("dropped", GuardState.encodeSet(state.dropped))
            .putString("asking", GuardState.encodeSet(state.asking))
            .putString("reminded", GuardState.encodeSet(state.reminded))
            .putInt("stateBoot", bootCount(context))
            .apply()
        version++
    }

    /** Still settling after a restart: by the clock since boot, not only the boot flag, which can come late. */
    private fun booting(context: Context, prefs: SharedPreferences): Boolean {
        val early = SystemClock.elapsedRealtime() < GuardRules.BOOT_WINDOW_MS && prefs.getInt("settledBoot", -1) != bootCount(context)
        return early || prefs.getBoolean("bootPending", false)
    }

    /** Android's boot counter; changes on every boot. */
    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun read(context: Context): Set<String> =
        (Settings.Secure.getString(context.contentResolver, KEY) ?: "").split(':').filter { it.isNotBlank() }.map(GuardRules::normal).toSet()

    private fun write(context: Context, services: Collection<String>) {
        Settings.Secure.putString(context.contentResolver, KEY, services.joinToString(":"))
        if (services.isNotEmpty()) Settings.Secure.putString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
    }

    /** True if the service's app is installed and loaded (its storage is mounted). */
    private fun available(context: Context, entry: String): Boolean {
        val cn = ComponentName.unflattenFromString(entry) ?: return false
        return runCatching { context.packageManager.getServiceInfo(cn, PackageManager.GET_META_DATA); true }.getOrDefault(false)
    }

    /** Watchdog apps that do the same job. */
    private val OTHER_WATCHDOGS = listOf("com.tvfilebridge.a11ywatchdog")
}
