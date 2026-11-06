package com.aktvhome.launcher.recent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug-only client support for the native Android task snapshot probe. */
internal data class SnapshotProbe(val detail: String, val bytes: ByteArray?)

internal suspend fun RecentProbeClient.snapshot(taskId: Int): SnapshotProbe =
    withContext(Dispatchers.IO) {
        transact(RecentTaskUserService.SNAPSHOT, { it.writeInt(taskId) }, {
            SnapshotProbe(it.readString().orEmpty(), it.createByteArray())
        })
    }
