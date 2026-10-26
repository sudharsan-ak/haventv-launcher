package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTest {

    private fun pkg(
        name: String,
        system: Boolean = true,
        state: PkgState = PkgState.DEFAULT,
        launcher: Boolean = false,
        permissions: List<PkgPermission> = emptyList(),
        persistent: Boolean = false,
        sharedUser: String? = null,
        voice: Boolean = false,
    ) = PackageFacts(name, name.substringAfterLast('.'), system, state, permissions, launcher, persistent, sharedUser, voice)

    private fun category(f: PackageFacts) = Cleanup.classify(f).category

    @Test fun statesFollowPackageManagersValues() {
        assertEquals(PkgState.DEFAULT, PkgState.fromSetting(0))
        assertEquals(PkgState.ENABLED, PkgState.fromSetting(1))
        assertEquals(PkgState.DISABLED, PkgState.fromSetting(2))
        assertEquals(PkgState.DISABLED_USER, PkgState.fromSetting(3))
        assertEquals(PkgState.DISABLED_UNTIL_USED, PkgState.fromSetting(4))
        assertTrue(PkgState.DEFAULT.on)
        assertFalse(PkgState.DISABLED_USER.on)
    }

    @Test fun knownPackagesGetHavensCategoryAndNote() {
        val c = Cleanup.classify(pkg("com.google.android.tvrecommendations"))
        assertEquals(CleanupCategory.ADS, c.category)
        assertNotNull(c.note)
        assertEquals(CleanupCategory.TELEMETRY, category(pkg("com.google.android.feedback")))
        assertEquals(CleanupCategory.FEATURES, category(pkg("com.google.android.apps.mediashell")))
        assertEquals(CleanupCategory.PRELOADED, category(pkg("com.netflix.ninja", launcher = true)))
    }

    @Test fun appsYouInstalledStayYoursEvenWhenHavenKnowsThem() {
        assertEquals(CleanupCategory.YOURS, category(pkg("com.netflix.ninja", system = false, launcher = true)))
        assertEquals(CleanupCategory.YOURS, category(pkg("org.smarttube.stable", system = false, launcher = true)))
    }

    @Test fun adsAndTrackersStayFlaggedWhenAMakerPreloadsThemAsOrdinaryApps() {
        assertEquals(CleanupCategory.ADS, category(pkg("com.sony.dtv.sonyselect", system = false)))
        assertEquals(CleanupCategory.TRACKERS, category(pkg("com.maker.acr", system = false)))
    }

    @Test fun unknownSystemPackagesAreNeverGuessedToBeTrackers() {
        assertEquals(CleanupCategory.SYSTEM, category(pkg("com.maker.mystery")))
        assertEquals(CleanupCategory.PRELOADED, category(pkg("com.maker.weather", launcher = true)))
        // An icon doesn't make Android itself a preloaded app.
        assertEquals(CleanupCategory.SYSTEM, category(pkg("com.android.providers.tv", launcher = true)))
        assertEquals(CleanupCategory.SYSTEM, category(pkg("com.maker.core", launcher = true, sharedUser = "android.uid.system")))
        assertEquals(CleanupCategory.SYSTEM, category(pkg("com.maker.always", launcher = true, persistent = true)))
    }

    @Test fun contentRecognitionIsSpottedByName() {
        assertEquals(CleanupCategory.TRACKERS, category(pkg("com.maker.acrservice")))
        assertEquals(CleanupCategory.TRACKERS, category(pkg("tv.samba.ssm")))
        // "acr" has to be a whole part of the name.
        assertEquals(CleanupCategory.SYSTEM, category(pkg("com.maker.acrobat")))
    }

    @Test fun scanSortsByCategoryThenName() {
        val scan = Cleanup.scan(listOf(pkg("com.maker.b"), pkg("org.mine", system = false), pkg("com.google.android.feedback"), pkg("com.maker.a")))
        assertEquals(listOf("com.google.android.feedback", "org.mine", "com.maker.a", "com.maker.b"), scan.map { it.facts.pkg })
        val counts = Cleanup.counts(scan)
        assertEquals(1, counts[CleanupCategory.TELEMETRY])
        assertEquals(2, counts[CleanupCategory.SYSTEM])
        assertEquals(0, counts[CleanupCategory.ADS])
    }

    @Test fun privacyListsTrackersTelemetryAndMicrophonePackages() {
        val mic = PkgPermission("android.permission.RECORD_AUDIO", granted = true)
        val scan = Cleanup.scan(
            listOf(
                pkg("com.google.android.feedback"),
                pkg("com.maker.acr"),
                pkg("com.google.android.katniss"),
                pkg("org.karaoke", system = false, permissions = listOf(mic)),
                pkg("com.maker.mystery"),
            ),
        )
        val findings = Cleanup.privacyFindings(scan)
        assertEquals(listOf("com.maker.acr", "com.google.android.feedback", "org.karaoke", "com.google.android.katniss"), findings.map { it.facts.pkg })
        assertEquals(setOf(PrivacyKind.VOICE), findings.last().kinds)
    }

    @Test fun evidenceSaysWhatAPackageAsksForNeverThatItListens() {
        val f = pkg(
            "com.google.android.katniss", voice = true,
            permissions = listOf(
                PkgPermission("android.permission.INTERNET", true),
                PkgPermission("android.permission.CAPTURE_AUDIO_HOTWORD", false),
                PkgPermission("android.permission.RECORD_AUDIO", true),
            ),
        )
        val evidence = Cleanup.privacy(Cleanup.classify(f))!!.evidence
        assertEquals("Is this TV's voice assistant service", evidence[0])
        assertEquals("Asks for: Microphone (granted)", evidence[1])
        assertEquals("Asks for: Hotword detection (not granted)", evidence[2])
        assertTrue(evidence[3].startsWith("Haven's note: "))
        assertTrue(evidence.none { it.contains("listen", ignoreCase = true) || it.contains("recording", ignoreCase = true) })
    }

    @Test fun packagesWithNothingToReportStayOffThePrivacyTab() {
        assertNull(Cleanup.privacy(Cleanup.classify(pkg("com.maker.mystery", permissions = listOf(PkgPermission("android.permission.INTERNET", true))))))
        val telemetry = Cleanup.privacy(Cleanup.classify(pkg("com.google.mainline.telemetry")))!!
        assertEquals(setOf(PrivacyKind.TELEMETRY), telemetry.kinds)
    }
}
