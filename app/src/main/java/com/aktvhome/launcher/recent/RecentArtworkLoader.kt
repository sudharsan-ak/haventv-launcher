package com.aktvhome.launcher.recent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Bounded artwork reader, modeled after Haven's existing NowPlayingRepository. */
internal object RecentArtworkLoader {
    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val MAX_DIMENSION = 480

    fun load(context: Context, raw: String?): Bitmap? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("content", "android.resource", "http", "https")) return null
        val bytes = runCatching {
            when (uri.scheme) {
                "http", "https" -> {
                    val conn = URL(raw).openConnection() as HttpURLConnection
                    conn.connectTimeout = 4_000
                    conn.readTimeout = 4_000
                    conn.instanceFollowRedirects = true
                    try {
                        if (conn.responseCode !in 200..299 ||
                            conn.contentLengthLong > MAX_BYTES) null
                        else conn.inputStream.use(::boundedBytes)
                    } finally {
                        conn.disconnect()
                    }
                }
                else -> context.contentResolver.openInputStream(uri)?.use(::boundedBytes)
            }
        }.getOrNull() ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < 1 || bounds.outHeight < 1 ||
                bounds.outWidth > 16000 || bounds.outHeight > 16000) return@runCatching null
            var sample = 1
            while (bounds.outWidth / sample > MAX_DIMENSION * 2 ||
                   bounds.outHeight / sample > MAX_DIMENSION * 2) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
            val factor = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height))
            if (factor == 1f) decoded else {
                val result = Bitmap.createScaledBitmap(decoded,
                    (decoded.width * factor).toInt().coerceAtLeast(1),
                    (decoded.height * factor).toInt().coerceAtLeast(1), true)
                if (result !== decoded) decoded.recycle()
                result
            }
        }.getOrNull()
    }

    fun scale(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.width < 1 || bitmap.height < 1) return null
        return runCatching {
            val factor = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height))
            val out = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * factor).toInt().coerceAtLeast(1),
                (bitmap.height * factor).toInt().coerceAtLeast(1), true)
            if (out.config == Bitmap.Config.HARDWARE)
                out.copy(Bitmap.Config.ARGB_8888, false) else out
        }.getOrNull()
    }

    private fun boundedBytes(stream: InputStream): ByteArray? {
        val data = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (data.size() + count > MAX_BYTES) return null
            data.write(buffer, 0, count)
        }
        return data.toByteArray()
    }
}
