package com.aktvhome.launcher.recent

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Parcel
import android.util.Log
import java.io.ByteArrayOutputStream

/** Debug build only: privileged Android task snapshot probe. */
internal object RecentSnapshotDiagnostics {
    private const val TAG = "HavenRecentProbe"
    /** Inspect an existing OS task snapshot without force-creating one. */
    private data class SnapshotProbe(val detail: String, val bytes: ByteArray? = null)

    private fun snapshot(taskId: Int, manager: () -> Any): SnapshotProbe {
        val tm = try {
            manager()
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
    fun write(reply: Parcel?, taskId: Int, manager: () -> Any) {
        val result = snapshot(taskId, manager)
        reply?.writeNoException()
        reply?.writeString(result.detail)
        reply?.writeByteArray(result.bytes)
    }
}
