package com.aktvhome.launcher.data

// Haven's own notes on packages found on TVs: what each one is, in a sentence, and its category.
// Only packages Haven is sure about are listed; everything else is sorted by what it is. Plain
// Kotlin, unit tested.

/** What Haven knows about one package. [voice] marks voice and microphone features. */
data class KnownPackage(val category: CleanupCategory, val note: String, val voice: Boolean = false)

object CleanupNotes {

    private val ads = mapOf(
        "com.google.android.tvrecommendations" to "Fills the Android TV home's recommendation rows, sponsored ones included",
        "com.google.android.leanbacklauncher.recommendations" to "Recommendation rows for the classic Android TV home",
        "com.sony.dtv.sonyselect" to "Sony Select: Sony's row of promoted apps",
        "com.google.android.adservices.api" to "Android's ad services: ad topics and ad measurement",
        "com.android.adservices.api" to "Android's ad services: ad topics and ad measurement",
    )

    private val telemetry = mapOf(
        "com.google.android.feedback" to "Sends feedback and crash reports to Google",
        "com.google.mainline.telemetry" to "Reports on Android's updatable system parts",
    )

    private val preloaded = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.google.android.youtube.tvunplugged" to "YouTube TV",
        "com.google.android.videos" to "Google's movie and show store",
        "com.google.android.play.games" to "Google Play Games",
        "com.netflix.ninja" to "Netflix",
        "com.netflix.partner.activation" to "Netflix sign-up helper added by the TV maker",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.disney.disneyplus" to "Disney+",
        "com.spotify.tv.android" to "Spotify",
    )

    private val features = mapOf(
        "com.google.android.apps.mediashell" to "Google Cast: casting from phones and laptops",
        "com.google.android.apps.tv.dreamx" to "Ambient mode screensaver, which can show suggestions",
        "com.google.android.backdrop" to "Backdrop screensaver",
        "com.android.dreams.basic" to "Basic screensaver",
        "com.google.android.tv.remote.service" to "Lets the phone remote app control this TV",
        "com.android.printspooler" to "Printing",
        "com.android.bips" to "Android's built-in print service",
        "com.google.android.marvin.talkback" to "TalkBack screen reader",
        "com.google.android.tts" to "Text to speech",
    )

    private val voice = mapOf(
        "com.google.android.katniss" to "Google app for TV: voice search and the Assistant from the remote's mic button",
    )

    private val known: Map<String, KnownPackage> = buildMap {
        ads.forEach { (p, n) -> put(p, KnownPackage(CleanupCategory.ADS, n)) }
        telemetry.forEach { (p, n) -> put(p, KnownPackage(CleanupCategory.TELEMETRY, n)) }
        preloaded.forEach { (p, n) -> put(p, KnownPackage(CleanupCategory.PRELOADED, n)) }
        features.forEach { (p, n) -> put(p, KnownPackage(CleanupCategory.FEATURES, n)) }
        voice.forEach { (p, n) -> put(p, KnownPackage(CleanupCategory.FEATURES, n, voice = true)) }
    }

    /** Name parts of automatic content recognition (ACR) services, which identify what's on screen. */
    private val ACR_PARTS = setOf("acr", "acrservice", "sambatv", "alphonso")

    fun lookup(pkg: String): KnownPackage? {
        known[pkg]?.let { return it }
        val parts = pkg.lowercase().split('.')
        if (parts.any { it in ACR_PARTS } || pkg.startsWith("tv.samba.")) {
            return KnownPackage(CleanupCategory.TRACKERS, "Its name is that of a content recognition (ACR) service, which identifies what's on screen")
        }
        return null
    }
}
