package com.aktvhome.launcher.data

import com.aktvhome.launcher.core.guard.GuardRules
import com.aktvhome.launcher.core.guard.GuardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.Predicate

/**
 * The Accessibility Watchdog tests from before GuardRules moved to Java (haven-core), run from
 * Kotlin against the Java code. Only Java syntax changed: no named arguments, and the stored
 * "loaded" checks are Java Predicates (a lambda written in the call converts by itself; a Kotlin
 * function kept in a variable doesn't).
 */
class GuardRulesTest {

    private val mapper = "flar2.homebutton/flar2.homebutton.MapperService"
    private val zank = "zank.remote/zank.remote.RemoteService"
    private val loaded = Predicate<String> { true }
    private val notLoaded = Predicate<String> { false }

    @Test fun firstRunTakesWhatIsOn() {
        val r = GuardRules.decide(null, setOf(mapper), loaded, false)
        assertTrue(r.restore.isEmpty())
        assertEquals(setOf(mapper), r.state.wanted)
    }

    @Test fun missingAtFirstCheckAfterBootIsRestored() {
        // Seen before the reboot doesn't count: the state after boot starts with nothing seen.
        val state = GuardState(setOf(mapper, zank), emptySet())
        val r = GuardRules.decide(state, emptySet(), loaded, true)
        assertEquals(setOf(mapper, zank), r.restore.toSet())
        assertEquals(setOf(mapper, zank), r.state.wanted)
    }

    @Test fun missingInBootWindowIsRestoredEvenIfSeenBefore() {
        // The race: the check runs before the boot flag is set, with the drive already loaded.
        val state = GuardState(setOf(mapper), setOf(mapper))
        val r = GuardRules.decide(state, emptySet(), loaded, true)
        assertEquals(listOf(mapper), r.restore)
        assertEquals(setOf(mapper), r.state.wanted)
    }

    @Test fun firstCheckAfterTheWindowStillRestores() {
        val state = GuardState(setOf(mapper), emptySet())
        assertEquals(listOf(mapper), GuardRules.decide(state, emptySet(), loaded, false).restore)
    }

    @Test fun appThatLoadsLateIsRestored() {
        var state = GuardState(setOf(zank), emptySet())
        // Drive not mounted yet: nothing to do, still wanted.
        var r = GuardRules.decide(state, emptySet(), notLoaded, true)
        assertTrue(r.restore.isEmpty())
        assertEquals(setOf(zank), r.state.wanted)
        state = r.state
        // Mounted after the boot window: it was never seen on this boot, so it's restored.
        r = GuardRules.decide(state, emptySet(), loaded, false)
        assertEquals(listOf(zank), r.restore)
    }

    @Test fun userSwitchOffLaterIsRespected() {
        var r = GuardRules.decide(GuardState(setOf(mapper), emptySet()), setOf(mapper), loaded, false)
        assertTrue(r.restore.isEmpty())
        // You turn it off: it was seen on, its app is loaded, the boot is long over.
        r = GuardRules.decide(r.state, emptySet(), loaded, false)
        assertTrue(r.restore.isEmpty())
        assertEquals(emptySet<String>(), r.state.wanted)
        // And it stays off.
        r = GuardRules.decide(r.state, emptySet(), loaded, false)
        assertTrue(r.restore.isEmpty())
    }

    @Test fun appUnmountedThenBackIsRestored() {
        var r = GuardRules.decide(GuardState(setOf(zank), emptySet()), setOf(zank), loaded, false)
        // Drive drops out and Android drops the service with it.
        r = GuardRules.decide(r.state, emptySet(), notLoaded, false)
        assertEquals(setOf(zank), r.state.wanted)
        r = GuardRules.decide(r.state, emptySet(), loaded, false)
        assertEquals(listOf(zank), r.restore)
    }

    @Test fun newServicesJoinTheWantedList() {
        val r = GuardRules.decide(GuardState(setOf(mapper), setOf(mapper)), setOf(mapper, zank), loaded, false)
        assertEquals(setOf(mapper, zank), r.state.wanted)
    }

    @Test fun shortAndLongFormsAreTheSameService() {
        assertEquals(mapper, GuardRules.normal("flar2.homebutton/.MapperService"))
        assertEquals(zank, GuardRules.normal(zank))
        // Wanted in short form, enabled in long form: not missing, one entry.
        val r = GuardRules.decide(GuardState(setOf("flar2.homebutton/.MapperService"), emptySet()), setOf(mapper), loaded, false)
        assertTrue(r.restore.isEmpty())
        assertEquals(setOf(mapper), r.state.wanted)
        // Saved twice in both forms: restored once.
        val twice = GuardRules.decide(GuardState(setOf("flar2.homebutton/.MapperService", mapper), emptySet()), emptySet(), loaded, true)
        assertEquals(listOf(mapper), twice.restore)
    }
}
