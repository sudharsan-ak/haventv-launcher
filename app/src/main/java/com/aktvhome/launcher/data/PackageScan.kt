package com.aktvhome.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.media.tv.TvInputManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every package on this TV with the facts [Cleanup] sorts them by: its flags, enabled state, the
 * permissions it asks for and whether it has an icon in the app list. Needs QUERY_ALL_PACKAGES;
 * packages that are switched off are included.
 */
class PackageScan(private val context: Context) {

    /** The last scan, classified; empty until [refresh] finishes once. */
    var scan by mutableStateOf<List<Classified>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun refresh() {
        scan = withContext(Dispatchers.IO) { Cleanup.scan(facts()) }
        loaded = true
    }

    /** One package's facts now, or null when it isn't installed. */
    fun facts(pkg: String): PackageFacts? {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(pkg, FLAGS) }.getOrNull() ?: return null
        return toFacts(info, launcherPackages(pm), voiceService())
    }

    /** Just [pkg]'s enabled state now (cheap), or null when it isn't installed. */
    fun state(pkg: String): PkgState? {
        val app = runCatching { context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull() ?: return null
        return state(pkg, app)
    }

    /** What Disable must leave alone on this TV right now: Home, keyboards, accessibility, inputs, Haven. */
    fun guardContext(): GuardContext {
        val pm = context.packageManager
        val cr = context.contentResolver
        val home = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL,
        ).map { it.activityInfo.packageName }.toSet()
        val keyboards = runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }
        }.getOrNull().orEmpty().toSet() + listOfNotNull(packageOf(Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)))
        val accessibility = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').mapNotNull(::packageOf).toSet()
        val inputs = runCatching {
            context.getSystemService(TvInputManager::class.java)?.tvInputList?.map { it.serviceInfo.packageName }
        }.getOrNull().orEmpty().toSet()
        return GuardContext(context.packageName, home, keyboards, accessibility, inputs)
    }

    private fun packageOf(component: String?): String? =
        component?.takeIf { it.isNotBlank() }?.let { ComponentName.unflattenFromString(it)?.packageName }

    private fun facts(): List<PackageFacts> {
        val pm = context.packageManager
        val launchers = launcherPackages(pm)
        val voice = voiceService()
        return pm.getInstalledPackages(FLAGS).mapNotNull { toFacts(it, launchers, voice) }
    }

    private fun toFacts(info: PackageInfo, launchers: Set<String>, voice: String?): PackageFacts? {
        val app = info.applicationInfo ?: return null
        val pm = context.packageManager
        val asked = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags ?: IntArray(asked.size)
        @Suppress("DEPRECATION") val sharedUser = info.sharedUserId
        return PackageFacts(
            pkg = info.packageName,
            label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName),
            system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            state = state(info.packageName, app),
            permissions = asked.mapIndexed { i, p ->
                PkgPermission(p, flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
            },
            hasLauncher = info.packageName in launchers,
            persistent = app.flags and ApplicationInfo.FLAG_PERSISTENT != 0,
            sharedUserId = sharedUser,
            voiceService = info.packageName == voice,
        )
    }

    /** The enabled state Android has for [pkg]; a package off in its own manifest reads as Off. */
    private fun state(pkg: String, app: ApplicationInfo): PkgState {
        val s = PkgState.fromSetting(runCatching { context.packageManager.getApplicationEnabledSetting(pkg) }.getOrDefault(0))
        return if (s == PkgState.DEFAULT && !app.enabled) PkgState.DISABLED else s
    }

    private fun launcherPackages(pm: PackageManager): Set<String> =
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.activityInfo.packageName }
        }.toSet()

    private fun voiceService(): String? =
        packageOf(Settings.Secure.getString(context.contentResolver, "voice_interaction_service"))

    private companion object {
        const val FLAGS = PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS
    }
}
