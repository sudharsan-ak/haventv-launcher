package com.aktvhome.launcher.recent

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService. This runs under the shell identity in a separate process.
 * It never stops or removes Android tasks and only starts when the user opens the switcher/lab.
 * All privileged operations are reached through its small, bounded Binder protocol.
 */
class RecentTaskUserService : Binder() {
    companion object {
        const val DESCRIPTOR = "com.aktvhome.launcher.recent.TaskProbe"
        const val LIST = IBinder.FIRST_CALL_TRANSACTION
        const val SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 1
        const val RESTORE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TAG = "HavenRecentProbe"
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in LIST..RESTORE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        try {
            when (code) {
                LIST -> {
                    val raw = shell("dumpsys activity recents")
                    val text = if (raw.contains("Recent #")) raw else shell("am stack list")
                    reply?.writeNoException()
                    reply?.writeString(text)
                }
                SNAPSHOT -> RecentSnapshotDiagnostics.write(
                    reply, data.readInt(), ::taskManager
                )
                RESTORE -> {
                    val ok = restore(data.readInt())
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Operation " + code + " failed", t)
            reply?.writeException(IllegalStateException(t.javaClass.simpleName + ": " + t.message))
        }
        return true
    }

    private fun shell(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val result = p.inputStream.bufferedReader().use { it.readText().take(180_000) }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        return result
    }

    private fun taskManager(): Any {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as IBinder
        val stub = Class.forName("android.app.IActivityTaskManager" + "$" + "Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }

    private fun restore(taskId: Int): Boolean = runCatching {
        val tm = taskManager()
        val method = tm.javaClass.methods.firstOrNull {
            it.name == "moveTaskToFront" && it.parameterTypes.any { p -> p == Int::class.javaPrimitiveType }
        } ?: return@runCatching false
        var firstInt = true
        val args = method.parameterTypes.map { type ->
            when {
                type == Int::class.javaPrimitiveType -> (if (firstInt) taskId else 0).also { firstInt = false }
                type == Boolean::class.javaPrimitiveType -> false
                type == Long::class.javaPrimitiveType -> 0L
                type == String::class.java -> "com.android.shell"
                else -> null
            }
        }.toTypedArray()
        method.invoke(tm, *args)
        true
    }.getOrDefault(false)

}
