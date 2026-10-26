package com.aktvhome.launcher.data

// Cleanup: what's installed on this TV, sorted into Haven's own categories, and the privacy
// evidence for each package. Plain Kotlin, unit tested; PackageScan gathers the facts on the device.

/** Haven's categories for a package, in the order the Scan filter lists them. */
enum class CleanupCategory(val label: String, val note: String) {
    ADS("Ads & promos", "Sponsored rows, promotions and the services behind them"),
    TELEMETRY("Telemetry", "Usage reports, diagnostics and feedback uploads"),
    TRACKERS("Viewing trackers", "Services that recognise what's on screen"),
    PRELOADED("Preloaded apps", "Apps that came with the TV"),
    FEATURES("Features", "Parts of the TV you may or may not use, like casting or voice"),
    YOURS("Installed by you", "Apps you added yourself"),
    SYSTEM("System", "Android and the TV's own parts"),
}

/** A package's enabled state, as Android keeps it. */
enum class PkgState(val label: String, val on: Boolean) {
    DEFAULT("On", true),
    ENABLED("On", true),
    DISABLED("Off", false),
    DISABLED_USER("Off", false),
    DISABLED_UNTIL_USED("Off until used", false);

    companion object {
        /** From PackageManager's COMPONENT_ENABLED_STATE_* values (0 to 4). */
        fun fromSetting(setting: Int): PkgState = when (setting) {
            1 -> ENABLED
            2 -> DISABLED
            3 -> DISABLED_USER
            4 -> DISABLED_UNTIL_USED
            else -> DEFAULT
        }
    }
}

/** One permission a package asks for, and whether Android has granted it. */
data class PkgPermission(val name: String, val granted: Boolean)

/** The facts Haven finds about one installed package. */
data class PackageFacts(
    val pkg: String,
    val label: String,
    /** Part of the TV's firmware (including firmware apps updated since). */
    val system: Boolean,
    val state: PkgState,
    val permissions: List<PkgPermission> = emptyList(),
    /** Has an entry in the TV's app list. */
    val hasLauncher: Boolean = false,
    /** Android keeps it running all the time. */
    val persistent: Boolean = false,
    val sharedUserId: String? = null,
    /** It's the TV's chosen voice assistant service. */
    val voiceService: Boolean = false,
)

/** A package with Haven's category for it and, if Haven knows it, a note on what it does. */
data class Classified(val facts: PackageFacts, val category: CleanupCategory, val note: String?)

/** Why a package shows on the Privacy tab. */
enum class PrivacyKind(val label: String) {
    TRACKER("Viewing tracker"),
    TELEMETRY("Telemetry"),
    VOICE("Voice & microphone"),
}

/** A package on the Privacy tab: why it's there and the evidence, one line each. */
data class PrivacyFinding(val facts: PackageFacts, val kinds: Set<PrivacyKind>, val evidence: List<String>)

object Cleanup {

    /** Permissions worth naming on the Privacy tab, with how to say that a package asks for them. */
    private val PRIVACY_PERMISSIONS = linkedMapOf(
        "android.permission.RECORD_AUDIO" to "Microphone",
        "android.permission.CAPTURE_AUDIO_HOTWORD" to "Hotword detection",
        "android.permission.CAPTURE_AUDIO_OUTPUT" to "Capturing the TV's sound",
        "android.permission.MANAGE_VOICE_KEYPHRASES" to "Voice keyphrases",
        "com.android.providers.tv.permission.ACCESS_WATCHED_PROGRAMS" to "Your watched-programs history",
        "android.permission.PACKAGE_USAGE_STATS" to "App usage history",
        "android.permission.READ_LOGS" to "System logs",
        "android.permission.DUMP" to "System diagnostics",
    )

    private val VOICE_PERMISSIONS = setOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.CAPTURE_AUDIO_HOTWORD",
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.MANAGE_VOICE_KEYPHRASES",
    )

    /**
     * Haven's category for [f]. Haven's notes come first; anything it doesn't know is sorted only by
     * what it is (yours, a preloaded app with an icon, or part of the system), never guessed to be
     * a tracker or telemetry. A known app you installed yourself stays yours.
     */
    fun classify(f: PackageFacts): Classified {
        val known = CleanupNotes.lookup(f.pkg)
        val category = when {
            known != null && (f.system || known.category in ALWAYS_KNOWN) -> known.category
            !f.system -> CleanupCategory.YOURS
            f.hasLauncher && !looksCore(f) -> CleanupCategory.PRELOADED
            else -> CleanupCategory.SYSTEM
        }
        return Classified(f, category, known?.note)
    }

    /** Categories Haven keeps even for a package that isn't part of the firmware (some makers preload that way). */
    private val ALWAYS_KNOWN = setOf(CleanupCategory.ADS, CleanupCategory.TELEMETRY, CleanupCategory.TRACKERS)

    /** Every package, classified, by category and then name. */
    fun scan(packages: Collection<PackageFacts>): List<Classified> =
        packages.map(::classify).sortedWith(compareBy({ it.category.ordinal }, { it.facts.label.lowercase() }, { it.facts.pkg }))

    fun counts(scan: Collection<Classified>): Map<CleanupCategory, Int> =
        CleanupCategory.entries.associateWith { c -> scan.count { it.category == c } }

    /**
     * The Privacy tab's entry for [c], or null when it has nothing to show. The evidence is what the
     * package asks Android for and what Haven knows about it. Asking for the microphone isn't using
     * it, so the lines only ever say "asks for".
     */
    fun privacy(c: Classified): PrivacyFinding? {
        val f = c.facts
        val kinds = mutableSetOf<PrivacyKind>()
        if (c.category == CleanupCategory.TRACKERS) kinds += PrivacyKind.TRACKER
        if (c.category == CleanupCategory.TELEMETRY) kinds += PrivacyKind.TELEMETRY
        val known = CleanupNotes.lookup(f.pkg)
        if (known?.voice == true || f.voiceService || f.permissions.any { it.name in VOICE_PERMISSIONS }) kinds += PrivacyKind.VOICE
        if (kinds.isEmpty()) return null

        val evidence = buildList {
            if (f.voiceService) add("Is this TV's voice assistant service")
            f.permissions.filter { it.name in PRIVACY_PERMISSIONS }
                .sortedBy { PRIVACY_PERMISSIONS.keys.indexOf(it.name) }
                .forEach { p -> add("Asks for: ${PRIVACY_PERMISSIONS.getValue(p.name)}" + if (p.granted) " (granted)" else " (not granted)") }
            c.note?.let { add("Haven's note: $it") }
            if (isEmpty()) add("Haven's category: ${c.category.label}")
        }
        return PrivacyFinding(f, kinds, evidence)
    }

    /** The Privacy tab: trackers first, then telemetry, then voice, each by name. */
    fun privacyFindings(scan: Collection<Classified>): List<PrivacyFinding> =
        scan.mapNotNull(::privacy).sortedWith(compareBy({ it.kinds.minOf { k -> k.ordinal } }, { it.facts.label.lowercase() }))

    /** Packages that are plainly part of Android itself, whatever they show. */
    internal fun looksCore(f: PackageFacts): Boolean =
        f.pkg == "android" || f.persistent || f.sharedUserId in CORE_SHARED_USERS ||
            CORE_PREFIXES.any { f.pkg.startsWith(it) }

    private val CORE_SHARED_USERS = setOf(
        "android.uid.system", "android.uid.phone", "android.uid.bluetooth", "android.uid.nfc",
        "android.uid.shell", "android.uid.networkstack", "android.uid.systemui", "android.media",
    )

    private val CORE_PREFIXES = listOf(
        "com.android.providers.", "com.google.android.providers.", "com.android.internal.", "com.android.server.",
        "android.auto_generated_rro_",
    )
}
