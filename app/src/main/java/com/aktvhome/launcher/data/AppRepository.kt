package com.aktvhome.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One openable app, with the art the TV already has for it. */
data class AppEntry(
    val pkg: String,
    val label: String,
    /** 16:9 TV banner, when the app ships one (most TV apps do). */
    val banner: ImageBitmap?,
    val icon: ImageBitmap,
    /** Strongest color in the app's art, used for glow and tint. */
    val color: Color,
)

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /** Every package with a TV or phone launcher entry, one entry per package. */
    suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val tv = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val phone = query(Intent.CATEGORY_LAUNCHER)
        val byPkg = LinkedHashMap<String, ResolveInfo>()
        // Prefer the TV entry when an app has both.
        for (ri in tv + phone) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) continue
            byPkg.putIfAbsent(pkg, ri)
        }
        byPkg.values.mapNotNull { ri -> runCatching { toEntry(ri) }.getOrNull() }
            .sortedBy { it.label.lowercase() }
    }

    fun launchIntent(pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun query(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    private fun toEntry(ri: ResolveInfo): AppEntry {
        val ai = ri.activityInfo
        val label = ri.loadLabel(pm).toString()
        val bannerDrawable = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        val banner = bannerDrawable?.toBitmap(BANNER_W, BANNER_H)
        val icon = ai.loadIcon(pm).toBitmap(ICON_SIZE, ICON_SIZE)
        val palette = Palette.from(banner ?: icon).maximumColorCount(16).generate()
        val swatch = palette.vibrantSwatch ?: palette.dominantSwatch
        val color = swatch?.let { Color(it.rgb) } ?: Color(0xFF5B7BFF)
        return AppEntry(
            pkg = ai.packageName,
            label = label,
            banner = banner?.asImageBitmap(),
            icon = icon.asImageBitmap(),
            color = color,
        )
    }

    private companion object {
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_SIZE = 160
    }
}
