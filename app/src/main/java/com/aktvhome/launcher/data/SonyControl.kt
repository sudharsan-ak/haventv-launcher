package com.aktvhome.launcher.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One TV setting: its current value and either a list of choices or a number range. */
data class SonySetting(
    val target: String,
    val current: String,
    val choices: List<String>,
    val min: Int?,
    val max: Int?,
    val step: Int,
) {
    val isRange: Boolean get() = min != null && max != null

    companion object {
        fun from(o: JSONObject): SonySetting {
            val cands = o.optJSONArray("candidate")
            val items = (0 until (cands?.length() ?: 0)).map { cands!!.getJSONObject(it) }
            val range = items.firstOrNull { it.has("max") }
            return SonySetting(
                o.optString("target"), o.optString("currentValue"),
                items.mapNotNull { it.optString("value").ifBlank { null } },
                range?.optInt("min"), range?.optInt("max"), range?.optInt("step", 1)?.coerceAtLeast(1) ?: 1,
            )
        }
    }
}

data class SonyVolume(val target: String, val volume: Int, val mute: Boolean, val min: Int, val max: Int)

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

    // ---- Picture, sound and power saving (read and change, as the TV's own menus do) ----

    /** Picture settings the TV offers, in its order, without the ones it marks unavailable. */
    suspend fun pictureSettings(): List<SonySetting>? =
        call("video", "getPictureQualitySettings", JSONArray().put(JSONObject().put("target", "")))
            ?.optJSONArray("result")?.optJSONArray(0)?.let { list ->
                (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.optBoolean("isAvailable", true) }
                    .map(SonySetting::from)
            }

    suspend fun setPicture(target: String, value: String): Boolean =
        call("video", "setPictureQualitySettings", settings(target, value)) != null

    /** "speaker", "hdmi" (audio system) or "speaker_hdmi". */
    suspend fun soundOutput(): String? =
        call("audio", "getSoundSettings", JSONArray().put(JSONObject().put("target", "outputTerminal")), "1.1")
            ?.optJSONArray("result")?.optJSONArray(0)?.optJSONObject(0)?.optString("currentValue")

    suspend fun setSoundOutput(value: String): Boolean =
        call("audio", "setSoundSettings", settings("outputTerminal", value), "1.1") != null

    /** Volume of whatever is playing the sound now (TV speakers or the audio system). */
    suspend fun volume(): SonyVolume? =
        call("audio", "getVolumeInformation", JSONArray())?.optJSONArray("result")?.optJSONArray(0)?.optJSONObject(0)?.let {
            SonyVolume(it.optString("target"), it.optInt("volume"), it.optBoolean("mute"), it.optInt("minVolume", 0), it.optInt("maxVolume", 100))
        }

    suspend fun setVolume(target: String, volume: Int): Boolean =
        call("audio", "setAudioVolume", JSONArray().put(JSONObject().put("target", target).put("volume", volume.toString()))) != null

    suspend fun setMute(mute: Boolean): Boolean =
        call("audio", "setAudioMute", JSONArray().put(JSONObject().put("status", mute))) != null

    suspend fun subwooferLevel(): Int? =
        call("audio", "getSpeakerSettings", JSONArray().put(JSONObject().put("target", "subwooferLevel")))
            ?.optJSONArray("result")?.optJSONArray(0)?.optJSONObject(0)?.optString("currentValue")?.toIntOrNull()

    suspend fun setSubwooferLevel(level: Int): Boolean =
        call("audio", "setSpeakerSettings", settings("subwooferLevel", level.toString())) != null

    /** "off", "low", "high" or "pictureOff". */
    suspend fun powerSaving(): String? =
        call("system", "getPowerSavingMode", JSONArray())?.optJSONArray("result")?.optJSONObject(0)?.optString("mode")

    suspend fun setPowerSaving(mode: String): Boolean =
        call("system", "setPowerSavingMode", JSONArray().put(JSONObject().put("mode", mode))) != null

    private fun settings(target: String, value: String): JSONArray =
        JSONArray().put(JSONObject().put("settings", JSONArray().put(JSONObject().put("target", target).put("value", value))))

    /** One call to the TV's control service; renews the login once if it's refused. Null on any error. */
    private suspend fun call(service: String, method: String, params: JSONArray, version: String = "1.0"): JSONObject? {
        if (!paired) return null
        val body = JSONObject().put("method", method).put("id", 1).put("params", params).put("version", version).toString()
        var reply = read(service, body)
        if (reply.first == 401 || reply.first == 403) {
            if (register(pin = null) != Result.DONE) return null
            reply = read(service, body)
        }
        if (reply.first != 200) return null
        return runCatching { JSONObject(reply.second) }.getOrNull()?.takeIf { !it.has("error") }
    }

    /** POSTs a call and returns (HTTP status, body). */
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
