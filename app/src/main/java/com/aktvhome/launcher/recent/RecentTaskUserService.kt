package com.aktvhome.launcher.recent

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService. This runs under the shell identity in a separate process.
 * It never uses force-stop and never runs until the user explicitly opens the lab.
 * All privileged operations are reached through its small, bounded Binder protocol.
 */
class RecentTaskUserService : Binder() {
    companion object {
        const val DESCRIPTOR = "com.aktvhome.launcher.recent.TaskProbe"
        const val LIST = IBinder.FIRST_CALL_TRANSACTION
        const val SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 1
        const val RESTORE = IBinder.FIRST_CALL_TRANSACTION + 2
        const val DISMISS = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TAG = "HavenRecentProbe"
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in LIST..DISMISS) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        try {
            when (code) {
                LIST -> {
                    val raw = shell("dumpsys activity recents")
                    val text = if (raw.contains("Recent #")) raw else shell("am stack list")
                    reply?.writeNoException()
                    reply?.writeString(text)
                }
                SNAPSHOT -> {
                    val bytes = snapshot(data.readInt())
                    reply?.writeNoException()
                    reply?.writeByteArray(bytes)
                }
                RESTORE -> {
                    val ok = restore(data.readInt())
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                }
                DISMISS -> {
                    val ok = remove(data.readInt())
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

    /** Removes the task only. Does not call forceStopPackage or am force-stop. */
    private fun remove(taskId: Int): Boolean = runCatching {
        val tm = taskManager()
        val result = tm.javaClass
            .getMethod("removeTask", Int::class.javaPrimitiveType)
            .invoke(tm, taskId)
        (result as? Boolean) ?: true
    }.getOrDefault(false)

    /**
     * Android's own saved task snapshot. Secure/app-theme snapshots are rejected.
     * Keep Binder payload small: at most 480px wide, JPEG, in memory only.
     */
    private fun snapshot(taskId: Int): ByteArray? = runCatching {
        val tm = taskManager()
        val candidates = tm.javaClass.methods.filter {
            it.name == "getTaskSnapshot" || it.name == "takeTaskSnapshot"
        }
        var snap: Any? = null
        for (method in candidates) {
            val types = method.parameterTypes
            if (types.isEmpty() || types[0] != Int::class.javaPrimitiveType) continue
            if (types.drop(1).any {
                    it != Boolean::class.javaPrimitiveType && it != Int::class.javaPrimitiveType
                }) continue
            val args = types.mapIndexed { i, type ->
                when {
                    i == 0 -> taskId
                    type == Boolean::class.javaPrimitiveType -> false
                    else -> 0
                }
            }.toTypedArray()
            snap = runCatching { method.invoke(tm, *args) }.getOrNull()
            if (snap != null) break
        }
        val shot = snap ?: return@runCatching null
        val real = runCatching {
            shot.javaClass.getMethod("isRealSnapshot").invoke(shot) as Boolean
        }.getOrDefault(true)
        if (!real) return@runCatching null
        val buffer = shot.javaClass.getMethod("getHardwareBuffer")
            .invoke(shot) as? HardwareBuffer ?: return@runCatching null
        try {
            val space = runCatching {
                shot.javaClass.getMethod("getColorSpace").invoke(shot) as? ColorSpace
            }.getOrNull()
            val hardware = Bitmap.wrapHardwareBuffer(buffer, space)
                ?: return@runCatching null
            val scaledWidth = minOf(480, hardware.width)
            val scaledHeight = maxOf(1, hardware.height * scaledWidth / hardware.width)
            val small = Bitmap.createScaledBitmap(hardware, scaledWidth, scaledHeight, true)
            ByteArrayOutputStream().use { out ->
                small.compress(Bitmap.CompressFormat.JPEG, 78, out)
                if (small !== hardware) small.recycle()
                out.toByteArray()
            }
        } finally {
            buffer.close()
        }
    }.getOrElse {
        Log.w(TAG, "Snapshot for " + taskId + " unavailable", it)
        null
    }
}
