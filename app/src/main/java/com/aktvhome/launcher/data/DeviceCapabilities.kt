package com.aktvhome.launcher.data

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aktvhome.launcher.HomeOverrideService

/**
 * What this TV lets Haven do ([Capabilities]), checked on the device itself: Sony's apps, Haven's
 * grants, the Home button service, display settings, HDMI-CEC and the device's own launcher.
 * Haven checks at start, whenever it comes back to the front (a setting may have changed) and on
 * "Check again"; the UI shows, offers to set up or hides each feature from [status].
 */
class DeviceCapabilities(private val context: Context, private val sony: SonyControl) {

    var status by mutableStateOf(Capabilities.evaluate(facts()))
        private set

    fun refresh() {
        status = Capabilities.evaluate(facts())
    }

    fun state(c: Capability): CapState = status.getValue(c).state

    fun available(c: Capability): Boolean = state(c) == CapState.AVAILABLE

    /** Shown at all (in settings): anything the TV can do, set up yet or not. */
    fun supported(c: Capability): Boolean = state(c) != CapState.UNSUPPORTED

    /** True on a Sony TV, where the TV's own picture, sound and power service is the one to use. */
    val isSony: Boolean get() = supported(Capability.TV_CONTROL)

    private fun facts(): DeviceFacts {
        val pm = context.packageManager
        return DeviceFacts(
            isSony = sonyInstalled(pm),
            sonyPaired = sony.paired,
            homeServiceOn = HomeOverride.isOn(context),
            secureSettings = granted(Manifest.permission.WRITE_SECURE_SETTINGS),
            tvListings = granted("android.permission.READ_TV_LISTINGS"),
            notifications = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
            files = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false),
            displaySettings = displaySettings(context) != null,
            hasCec = pm.hasSystemFeature("android.hardware.hdmi.cec"),
            cecOn = Settings.Global.getInt(context.contentResolver, "hdmi_control_enabled", 1) == 1,
            launcher = stockLauncher(context)?.let { cn ->
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(cn.packageName, 0)).toString() }.getOrDefault(cn.packageName)
            },
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Sony's TV settings apps, present on every BRAVIA and nowhere else. */
        private val SONY_PACKAGES = listOf("com.sony.dtv.configsettings", "com.sony.dtv.settingsdisplay")

        fun sonyInstalled(pm: PackageManager): Boolean = SONY_PACKAGES.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }

        /** The device's own display settings (resolution, HDR, screen position), if it has a page for them. */
        fun displaySettings(context: Context): Intent? =
            Intent(Settings.ACTION_DISPLAY_SETTINGS).takeIf { it.resolveActivity(context.packageManager) != null }

        /**
         * The device's own home screen: the launcher Android would use if Haven weren't there (Google
         * TV, the classic Android TV launcher or a maker's own). The Home button service watches for it.
         */
        fun stockLauncher(context: Context): ComponentName? {
            val pm = context.packageManager
            return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo }
                .filter { it.packageName != context.packageName }
                // Settings' fallback home only shows during setup; any real launcher comes first.
                .sortedBy { it.packageName == "com.android.tv.settings" || it.packageName.endsWith(".fallbackhome") }
                .firstOrNull()
                ?.let { ComponentName(it.packageName, it.name) }
        }
    }
}

/**
 * The TV controls for devices without Sony's service: this device's own volume, and sleep or
 * Android's power menu through the Home button service.
 */
object GenericControl {

    private fun audio(context: Context) = context.getSystemService(AudioManager::class.java)

    fun volume(context: Context): Int = audio(context).getStreamVolume(AudioManager.STREAM_MUSIC)

    fun maxVolume(context: Context): Int = audio(context).getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun muted(context: Context): Boolean = audio(context).isStreamMute(AudioManager.STREAM_MUSIC)

    fun setVolume(context: Context, level: Int) {
        audio(context).setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, maxVolume(context)), AudioManager.FLAG_SHOW_UI)
    }

    fun toggleMute(context: Context) {
        audio(context).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
    }

    /** Puts the device to sleep (with HDMI-CEC on, the display follows). False without the service. */
    fun sleep(): Boolean = HomeOverrideService.sleep()

    /** Opens Android's own power menu (it has Restart; apps can't restart a device). */
    fun powerMenu(): Boolean = HomeOverrideService.powerMenu()
}
