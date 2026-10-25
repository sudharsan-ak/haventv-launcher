package com.aktvhome.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes

/** Legacy shortcut providers used by Button Mapper's Shortcuts picker. */
abstract class HavenShortcutActivity(
    @StringRes private val label: Int,
    private val havenAction: String,
) : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, MainActivity::class.java)
            .setAction(havenAction)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val result = Intent()
            .putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(label))
            .putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_haven),
            )
        setResult(RESULT_OK, result)
        finish()
    }
}

class SearchShortcutActivity : HavenShortcutActivity(
    R.string.haven_search,
    "com.aktvhome.launcher.action.OPEN_SEARCH",
)

class SettingsShortcutActivity : HavenShortcutActivity(
    R.string.haven_settings,
    "com.aktvhome.launcher.action.OPEN_SETTINGS",
)
