package com.aktvhome.launcher.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Power off and restart through the BRAVIA's own control API (Sony's IP control), reached on the
 * TV's loopback address.
 *
 * Haven registers with the TV once: the TV shows a PIN, you type it into Haven, and Haven appears
 * under Settings › Remote device settings. That registration is permanent; only the login cookie it
 * hands out expires (about two weeks). A registered device gets a fresh cookie by registering again
 * with no PIN, so Haven renews it daily and whenever a command is refused.
 *
 * The cookie lives in its own preferences, which the backup file leaves out.
 */
class SonyControl(context: Context) {

    enum class Result { DONE, NEEDS_PAIRING, FAILED }

    private val prefs = context.getSharedPreferences("sony_auth", Context.MODE_PRIVATE)

    val paired: Boolean get() = prefs.getString("cookie", null) != null

    /** Asks the TV to register Haven. Returns NEEDS_PAIRING when the TV is now showing a PIN. */
    suspend fun startPairing(): Result = register(pin = null)

    /** Finishes pairing with the PIN on the TV screen. */
    suspend fun submitPin(pin: String): Result = register(pin)

    suspend fun powerOff(): Result = command("system", """{"method":"setPowerStatus","id":1,"params":[{"status":false}],"version":"1.0"}""")

    suspend fun restart(): Result = command("system", """{"method":"requestReboot","id":1,"params":[],"version":"1.0"}""")

    /**
     * One-time, read-only look at what the TV's control service reports (picture, sound, screen,
     * power saving, network), saved to Download/Haven/sony-report.json for designing the Quick
     * settings pages. Only "get" calls; nothing on the TV changes.
     */
    suspend fun writeReport(file: java.io.File) {
        if (!paired) return
        val calls = listOf(
            "video" to """{"method":"getPictureQualitySettings","id":1,"params":[{"target":""}],"version":"1.0"}""",
            "video" to """{"method":"getScreenRotation","id":1,"params":[],"version":"1.0"}""",
            "video" to """{"method":"getMethodTypes","id":1,"params":[""],"version":"1.0"}""",
            "audio" to """{"method":"getSoundSettings","id":1,"params":[{"target":""}],"version":"1.1"}""",
            "audio" to """{"method":"getSpeakerSettings","id":1,"params":[{"target":""}],"version":"1.0"}""",
            "audio" to """{"method":"getVolumeInformation","id":1,"params":[],"version":"1.0"}""",
            "audio" to """{"method":"getMethodTypes","id":1,"params":[""],"version":"1.0"}""",
            "videoScreen" to """{"method":"getSceneSetting","id":1,"params":[],"version":"1.0"}""",
            "videoScreen" to """{"method":"getBannerMode","id":1,"params":[],"version":"1.0"}""",
            "videoScreen" to """{"method":"getAudioSourceScreen","id":1,"params":[],"version":"1.0"}""",
            "videoScreen" to """{"method":"getMultiScreenMode","id":1,"params":[],"version":"1.1"}""",
            "videoScreen" to """{"method":"getMethodTypes","id":1,"params":[""],"version":"1.0"}""",
            "system" to """{"method":"getPowerSavingMode","id":1,"params":[],"version":"1.0"}""",
            "system" to """{"method":"getNetworkSettings","id":1,"params":[{"netif":""}],"version":"1.0"}""",
            "system" to """{"method":"getLEDIndicatorStatus","id":1,"params":[],"version":"1.0"}""",
            "system" to """{"method":"getSystemSupportedFunction","id":1,"params":[],"version":"1.0"}""",
            "system" to """{"method":"getMethodTypes","id":1,"params":[""],"version":"1.0"}""",
            "avContent" to """{"method":"getPlayingContentInfo","id":1,"params":[],"version":"1.0"}""",
            "avContent" to """{"method":"getCurrentExternalInputsStatus","id":1,"params":[],"version":"1.1"}""",
        )
        val out = StringBuilder("{\n")
        calls.forEachIndexed { i, (service, body) ->
            var reply = read(service, body)
            if (reply.first == 401 || reply.first == 403) {
                register(pin = null)
                reply = read(service, body)
            }
            val method = Regex("\"method\":\"(\\w+)\"").find(body)?.groupValues?.get(1)
            val json = reply.second.ifBlank { "null" }.takeIf { it.trimStart().startsWith("{") } ?: org.json.JSONObject.quote(reply.second)
            out.append("  \"$service.$method\": {\"http\": ${reply.first}, \"reply\": $json}")
            out.append(if (i < calls.lastIndex) ",\n" else "\n")
        }
        out.append("}\n")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
        }
    }

    /** POSTs a read-only call and returns (HTTP status, body). */
    private suspend fun read(service: String, body: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open(service)
            cookie()?.let { conn.setRequestProperty("Cookie", it) }
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() }.getOrNull()
                code to (text ?: "")
            } finally {
                conn.disconnect()
            }
        }.getOrElse { -1 to (it.message ?: "no answer") }
    }

    /** Renews the login once a day while the TV is on (no PIN needed once paired). */
    suspend fun refreshIfDue() {
        if (!paired) return
        if (System.currentTimeMillis() - prefs.getLong("cookieAt", 0L) < DAY_MS) return
        register(pin = null)
    }

    /** Sends a command; if the login has expired, renews it and tries once more. */
    private suspend fun command(service: String, body: String): Result {
        if (!paired) return Result.NEEDS_PAIRING
        val first = post(service, body, cookie())
        if (first == 200) return Result.DONE
        if (first != 401 && first != 403) return Result.FAILED
        return when (register(pin = null)) {
            Result.DONE -> if (post(service, body, cookie()) == 200) Result.DONE else Result.FAILED
            Result.NEEDS_PAIRING -> {
                // The TV no longer knows Haven (deregistered or reset): pair again.
                prefs.edit().remove("cookie").apply()
                Result.NEEDS_PAIRING
            }
            Result.FAILED -> Result.FAILED
        }
    }

    private suspend fun register(pin: String?): Result = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open("accessControl")
            if (pin != null) {
                // Sony takes the on-screen PIN as the password of HTTP Basic auth, with no username.
                val credentials = Base64.encodeToString(":$pin".toByteArray(), Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $credentials")
            }
            try {
                conn.outputStream.use { it.write(REGISTER.toByteArray()) }
                when (conn.responseCode) {
                    200 -> {
                        val cookie = conn.headerFields["Set-Cookie"]?.firstOrNull()?.substringBefore(";")
                        if (cookie == null) Result.FAILED
                        else {
                            prefs.edit().putString("cookie", cookie).putLong("cookieAt", System.currentTimeMillis()).apply()
                            Result.DONE
                        }
                    }
                    401, 403 -> Result.NEEDS_PAIRING
                    else -> Result.FAILED
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(Result.FAILED)
    }

    /** POSTs [body] to /sony/[service]; returns the HTTP status, or -1 if the TV didn't answer. */
    private suspend fun post(service: String, body: String, cookie: String?): Int = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open(service)
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                // Sony reports some refusals as HTTP 200 with an "error" body.
                if (code == 200 && conn.inputStream.bufferedReader().readText().contains("\"error\"")) 500 else code
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1)
    }

    private fun cookie(): String? = prefs.getString("cookie", null)

    private fun open(service: String): HttpURLConnection =
        (URL("http://$HOST/sony/$service").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

    private companion object {
        /** The TV's own address; allowed for plain http in network_security_config.xml. */
        const val HOST = "127.0.0.1"
        const val DAY_MS = 24 * 3_600_000L
        const val REGISTER =
            """{"method":"actRegister","id":1,"version":"1.0","params":[{"clientid":"haven:1","nickname":"Haven"},[{"clientid":"haven:1","value":"yes","nickname":"Haven","function":"WOL"}]]}"""
    }
}
