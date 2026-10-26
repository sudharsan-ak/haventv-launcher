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
        "com.sony.dtv.promos" to "Sony's promotions",
        "com.google.android.adservices.api" to "Android's ad services: ad topics and ad measurement",
        "com.android.adservices.api" to "Android's ad services: ad topics and ad measurement",
    )

    private val telemetry = mapOf(
        "com.google.android.feedback" to "Sends feedback and crash reports to Google",
        "com.google.mainline.telemetry" to "Reports on Android's updatable system parts",
        "com.sonyericsson.idd.agent" to "Sony's usage data agent, which reports how the TV is used",
        "com.sony.dtv.sonybugreportsys" to "Sony's bug report collector",
        "com.sony.dtv.sonyloglevelsettingsys" to "Sony's diagnostic logging settings",
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
        "com.apple.atve.sony.appletv" to "Apple TV",
        "com.sonypicturescore" to "Sony Pictures Core",
        "com.sony.dtv.multiscreendemo" to "Sony's store demo app",
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
        "com.sony.dtv.airplayapp" to "AirPlay from Apple devices",
        "com.sony.dtv.homekit" to "Apple Home (HomeKit) control",
        "com.sony.dtv.gestureapp" to "Hand gestures with the BRAVIA CAM",
        "com.sony.dtv.sensingcamera.settings" to "BRAVIA CAM settings",
        "com.sony.dtv.sensingcamera.fwupdate" to "BRAVIA CAM software updates",
        "com.sony.dtv.sensingfusionservice" to "BRAVIA CAM sensing: who's watching and how far away",
        "com.sony.dtv.distancealert" to "BRAVIA CAM warning when you sit too close",
        "com.sony.dtv.ecodashboard" to "Eco dashboard",
        "com.sony.dtv.helpguidecontents" to "The built-in help guide",
        "com.sony.dtv.smarthelp" to "Sony's help app",
        "com.sony.dtv.customersupport" to "Sony's customer support app",
        "com.sony.dtv.accessibility.tutorial" to "Accessibility tutorial",
        "com.sony.dtv.hddmgr" to "Recording to a USB hard drive",
        "com.sony.dtv.seeds.iot" to "Control from smart speakers and Sony's phone app",
        "com.sony.dtv.seeds.iot.remotestart" to "Turning the TV on from smart speakers or Sony's phone app",
        "com.sony.huey.dlna.module" to "Playing media from other devices on your network (DLNA)",
        "com.sony.huey.dlna.renderersettings" to "Settings for playing media from other devices (DLNA)",
        "com.sonyericsson.dlna" to "Playing media from other devices on your network (DLNA)",
    )

    private val voice = mapOf(
        "com.google.android.katniss" to "Google app for TV: voice search and the Assistant from the remote's mic button",
        "com.sony.dtv.voicesearch" to "Sony's voice search",
        "com.sony.dtv.voicerecognitionconfirmation" to "Sony's voice recognition consent screen",
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
