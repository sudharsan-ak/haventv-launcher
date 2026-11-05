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
 * It never stops or removes Android tasks and only starts when the user opens the switcher/lab.
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
                    val result = snapshot(data.readInt())
                    reply?.writeNoException()
                    reply?.writeString(result.detail)
                    reply?.writeByteArray(result.bytes)
                }
                RESTORE -> {
                    val ok = restore(data.readInt())
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                }
                DISMISS -> {
                    // Reserved legacy transaction. Deleting an Android task can finish
                    // its activities, so the final switcher never performs this action.
                    data.readInt()
                    reply?.writeNoException()
                    reply?.writeInt(0)
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

    /** Inspect an existing OS task snapshot without force-creating one. */
    private data class SnapshotProbe(val detail: String, val bytes: ByteArray? = null)

    private fun snapshot(taskId: Int): SnapshotProbe {
        val tm = try {
            taskManager()
        } catch (e: Exception) {
            return SnapshotProbe("ActivityTaskManager unavailable: " + e.javaClass.simpleName)
        }
        val methods = tm.javaClass.methods.filter {
            it.name == "getTaskSnapshot" &&
                it.parameterTypes.isNotEmpty() &&
                it.parameterTypes.first() == Int::class.javaPrimitiveType
        }
        if (methods.isEmpty()) return SnapshotProbe("No getTaskSnapshot method exposed by this TV")
        val failures = mutableListOf<String>()
        for (method in methods) {
            val types = method.parameterTypes
            val signature = method.name + "(" + types.joinToString { it.simpleName } + ")"
            if (types.drop(1).any { it != Boolean::class.javaPrimitiveType }) {
                failures += signature + ": unsupported parameters"
                continue
            }
            val args = arrayOfNulls<Any>(types.size)
            args[0] = taskId
            for (i in 1 until args.size) args[i] = false
            try {
                val shot = method.invoke(tm, *args)
                if (shot == null) {
                    failures += signature + ": returned null (no cached snapshot or denied)"
                    continue
                }
                val real = runCatching {
                    shot.javaClass.getMethod("isRealSnapshot").invoke(shot) as Boolean
                }.getOrNull()
                if (real != true) {
                    failures += signature + ": not confirmed as a real snapshot"
                    continue
                }
                val buffer = shot.javaClass.getMethod("getHardwareBuffer")
                    .invoke(shot) as? HardwareBuffer
                if (buffer == null) {
                    failures += signature + ": no hardware buffer"
                    continue
                }
                try {
                    val space = runCatching {
                        shot.javaClass.getMethod("getColorSpace").invoke(shot) as? ColorSpace
                    }.getOrNull()
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, space)
                    if (hardware == null || hardware.width <= 0 || hardware.height <= 0) {
                        failures += signature + ": buffer not readable"
                        continue
                    }
                    val w = minOf(480, hardware.width)
                    val h = maxOf(1, hardware.height * w / hardware.width)
                    val small = Bitmap.createScaledBitmap(hardware, w, h, true)
                    val bytes = ByteArrayOutputStream().use { out ->
                        small.compress(Bitmap.CompressFormat.JPEG, 78, out)
                        if (small !== hardware) small.recycle()
                        out.toByteArray()
                    }
                    return SnapshotProbe(signature + ": real snapshot, " + w + "x" + h, bytes)
                } finally {
                    buffer.close()
                }
            } catch (e: Exception) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                failures += signature + ": " + cause.javaClass.simpleName +
                    (cause.message?.take(100)?.let { " - " + it } ?: "")
            }
        }
        val detail = failures.joinToString(" | ").take(500)
        Log.i(TAG, "Task snapshot " + taskId + ": " + detail)
        return SnapshotProbe(detail)
    }
}
