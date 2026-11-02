package com.aktvhome.launcher.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

/** Temporary paired local-network transfer. Open only while the user requests an upload. */
class WallpaperUploadServer(
    private val scope: CoroutineScope,
    private val onPhoto: (ByteArray, String) -> Unit,
) {
    var address by mutableStateOf("")
        private set
    var code by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set
    var isRunning by mutableStateOf(false)
        private set

    private var server: ServerSocket? = null
    private var failedCodes = 0

    fun start(): Boolean {
        stop()
        val host = localAddress()
        if (host == null) {
            error = "Connect the TV and your device to the same local network."
            return false
        }
        return runCatching {
            val listener = ServerSocket(0)
            listener.soTimeout = 1_000
            server = listener
            failedCodes = 0
            code = (100_000 + SecureRandom().nextInt(900_000)).toString()
            address = "http://" + host + ":" + listener.localPort + "/"
            error = ""
            isRunning = true
            val expiresAt = System.currentTimeMillis() + 5 * 60_000L
            scope.launch(Dispatchers.IO) {
                try {
                    while (server === listener && isRunning && System.currentTimeMillis() < expiresAt) {
                        try {
                            listener.accept().use { handle(it) }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Recheck the five-minute expiry.
                        } catch (_: SocketException) {
                            break
                        } catch (_: Exception) {
                            // Malformed or interrupted transfers cannot crash Haven.
                        }
                    }
                } finally {
                    if (server === listener) stop()
                }
            }
            true
        }.getOrElse {
            stop()
            error = "Couldn't open the upload page: " + (it.message ?: "network error")
            false
        }
    }

    fun stop() {
        isRunning = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 12_000
        val input = client.getInputStream()
        val request = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        var ended = false
        for (i in 0 until 32) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) { ended = true; break }
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        if (!ended) return
        when {
            request.startsWith("GET / ") -> reply(client, 200, "text/html; charset=utf-8", PAGE)
            request.startsWith("POST /upload ") -> {
                if (!isRunning || failedCodes >= 5) {
                    reply(client, 429, "text/plain", "Upload closed. Reopen from Haven.")
                    return
                }
                if (headers["x-haven-code"] != code) {
                    failedCodes++
                    reply(client, 403, "text/plain", "Incorrect pairing code.")
                    if (failedCodes >= 5) stop()
                    return
                }
                val size = headers["content-length"]?.toIntOrNull()
                if (size == null || size !in 1..MAX_BYTES) {
                    reply(client, 413, "text/plain", "Choose an image under 16 MB.")
                    return
                }
                val photo = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val n = input.read(photo, offset, size - offset)
                    if (n < 0) return
                    offset += n
                }
                val name = runCatching {
                    URLDecoder.decode(headers["x-file-name"] ?: "My photo", "UTF-8").take(80)
                }.getOrDefault("My photo")
                reply(client, 200, "text/plain", "Photo sent. Check your TV to preview and apply it.")
                onPhoto(photo, name)
                stop()
            }
            else -> reply(client, 404, "text/plain", "Not found.")
        }
    }

    private fun readLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (output.size() < 8_192) {
            val b = input.read()
            if (b < 0) return null
            if (b == 10) return output.toString("UTF-8").trimEnd('\r')
            output.write(b)
        }
        return null
    }

    private fun reply(client: Socket, code: Int, type: String, content: String) {
        val body = content.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 " + code + " OK\r\n" +
            "Content-Type: " + type + "\r\nContent-Length: " + body.size +
            "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
        private val PAGE = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Send photo to Haven</title>
            <style>
            body{margin:0;background:#171c27;color:#f8fbff;font:16px system-ui,sans-serif}
            main{max-width:420px;margin:7vh auto;padding:28px;background:#29313f;border-radius:20px}
            h1{font-size:25px}p{color:#becadd;line-height:1.5}label{display:block;margin:16px 0 7px}
            input{box-sizing:border-box;width:100%;padding:12px;border-radius:8px;border:1px solid #8797aa;background:#161d2a;color:white}
            button{margin-top:18px;width:100%;padding:14px;background:#adf2fb;color:#14202b;border:0;border-radius:10px;font-weight:700}
            #result{margin-top:16px;white-space:pre-line}
            </style></head><body><main><h1>Send a photo to Haven</h1>
            <p>Send directly to your TV over your home network. No cloud account needed.</p>
            <label for="pin">Pairing code from the TV</label>
            <input id="pin" inputmode="numeric" maxlength="6" autocomplete="off">
            <label for="photo">Photo (JPG, PNG or WebP, maximum 16 MB)</label>
            <input id="photo" type="file" accept="image/jpeg,image/png,image/webp">
            <button id="send">Send to TV</button><div id="result" role="status"></div></main>
            <script>
            document.querySelector('#send').onclick=async function(){
              const f=document.querySelector('#photo').files[0];
              const pin=document.querySelector('#pin').value.trim();
              const out=document.querySelector('#result');
              if(!f||pin.length!==6){out.textContent='Choose a photo and enter the six-digit code.';return}
              if(f.size>16777216){out.textContent='Photo is too large.';return}
              out.textContent='Sending...';
              try {
                const r=await fetch('/upload',{method:'POST',headers:{
                  'X-Haven-Code':pin,'X-File-Name':encodeURIComponent(f.name),
                  'Content-Type':'application/octet-stream'},body:f});
                out.textContent=await r.text();
              }catch(e){out.textContent='Cannot reach the TV. Check that both devices are on the same network.'}
            }
            </script></body></html>
        """.trimIndent()
    }
}
