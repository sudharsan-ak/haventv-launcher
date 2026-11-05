package com.aktvhome.launcher.recent

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Client for the optional Shizuku UserService. No privileged work runs in Haven's UI process. */
class RecentProbeClient(private val context: Context) {
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context, RecentTaskUserService::class.java)
    ).processNameSuffix("haven_recents_probe").tag("haven_recents_probe").version(1).daemon(false)

    @Volatile private var remote: IBinder? = null
    private var connection: ServiceConnection? = null

    fun status(): String = runCatching {
        when {
            !Shizuku.pingBinder() -> "Shizuku not running"
            Shizuku.isPreV11() -> "Shizuku version too old"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku permission required"
            remote?.isBinderAlive == true -> "UserService connected"
            else -> "Shizuku ready, connect to test"
        }
    }.getOrDefault("Shizuku unavailable")

    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        if (ready()) return
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            Shizuku.requestPermission(6214)
        }
    }

    suspend fun connect(): Boolean {
        if (!ready()) return false
        if (remote?.isBinderAlive == true) return true
        // Shizuku binder connection callbacks run on the main thread.
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        remote = service
                        if (continuation.isActive) continuation.resume(service?.isBinderAlive == true)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        remote = null
                    }
                }
                connection = conn
                try {
                    Shizuku.bindUserService(args, conn)
                } catch (e: Exception) {
                    Log.w("HavenRecentProbe", "Shizuku bind failed", e)
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        } ?: false
    }

    fun close() {
        val conn = connection ?: return
        connection = null
        remote = null
        runCatching { Shizuku.unbindUserService(args, conn, false) }
    }

    suspend fun list(): String = withContext(Dispatchers.IO) {
        transact(RecentTaskUserService.LIST, {}, { it.readString().orEmpty() })
    }

    suspend fun snapshot(taskId: Int): ByteArray? = withContext(Dispatchers.IO) {
        transact(RecentTaskUserService.SNAPSHOT, { it.writeInt(taskId) }, {
            it.createByteArray()
        })
    }

    suspend fun restore(taskId: Int): Boolean = withContext(Dispatchers.IO) {
        transact(RecentTaskUserService.RESTORE, { it.writeInt(taskId) }, { it.readInt() != 0 })
    }

    suspend fun dismiss(taskId: Int): Boolean = withContext(Dispatchers.IO) {
        transact(RecentTaskUserService.DISMISS, { it.writeInt(taskId) }, { it.readInt() != 0 })
    }

    private fun <T> transact(
        operation: Int, write: (Parcel) -> Unit, read: (Parcel) -> T,
    ): T {
        val binder = remote?.takeIf { it.isBinderAlive } ?: error("UserService is not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RecentTaskUserService.DESCRIPTOR)
            write(data)
            check(binder.transact(operation, data, reply, 0)) { "Binder transaction failed" }
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
