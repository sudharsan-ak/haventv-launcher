package com.aktvhome.launcher.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.aktvhome.launcher.core.corner.CornerKind
import com.aktvhome.launcher.core.corner.CornerQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The top-right corner, shared by the alert banners (drawn by the Home button service) and the
 * toasts on Haven's Home (the Last night recap, accessibility reminders). Both run in Haven's
 * process, so they take turns through this one [CornerQueue] (Java, in haven-core): one toast at
 * a time, the most urgent waiting one next.
 */
object Corner {

    private val queue = CornerQueue()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Bumped whenever the corner may have come free, so Home's toasts look again. */
    var version by mutableIntStateOf(0)
        private set

    fun want(kind: CornerKind) = queue.want(kind)

    fun tryStart(kind: CornerKind): Boolean = queue.tryStart(kind)

    fun withdraw(kind: CornerKind) {
        queue.withdraw(kind)
        changed()
    }

    fun finish(kind: CornerKind) {
        queue.finish(kind)
        changed()
    }

    /** Calls [listener] (on the main thread) whenever the corner may have come free. */
    fun listen(listener: () -> Unit) = listeners.add(listener)

    fun unlisten(listener: () -> Unit) = listeners.remove(listener)

    private fun changed() {
        main.post {
            version++
            listeners.forEach { it() }
        }
    }
}
