package com.aktvhome.launcher.recent

import android.os.Parcel

/** Release build has no native snapshot/reflection implementation. */
internal object RecentSnapshotDiagnostics {
    fun write(reply: Parcel?, taskId: Int, manager: () -> Any) {
        reply?.writeNoException()
        reply?.writeString("Task-snapshot diagnostics are not available in release builds")
        reply?.writeByteArray(null)
    }
}
