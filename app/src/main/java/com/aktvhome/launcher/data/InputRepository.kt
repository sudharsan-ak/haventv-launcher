package com.aktvhome.launcher.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

data class TvInput(val id: String, val label: String)

class InputRepository(private val context: Context) {

    /** Physical HDMI ports, in port order. Devices plugged behind a port are not listed separately. */
    fun loadInputs(): List<TvInput> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return runCatching {
            tim.tvInputList
                .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
                .map { TvInput(it.id, it.loadLabel(context).toString()) }
                .sortedBy { it.label }
        }.getOrDefault(emptyList())
    }

    fun switchIntent(input: TvInput): Intent =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
