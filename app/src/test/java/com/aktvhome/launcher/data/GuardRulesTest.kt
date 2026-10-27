package com.aktvhome.launcher.data

import com.aktvhome.launcher.core.guard.GuardRules
import com.aktvhome.launcher.core.guard.GuardState
import com.aktvhome.launcher.core.guard.Keep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.Predicate

/**
 * The Accessibility Watchdog's rules (Java, in haven-core), called from Kotlin the way
 * AccessibilityGuard calls them: the boot and USB cases from before the Accessibility tab, plus
 * asking instead of guessing when a service is switched off outside Haven.
 */
class GuardRulesTest {

    private val self = "com.aktvhome.launcher"
    private val haven = "$self/$self.HomeOverrideService"
    private val mapper = "flar2.homebutton/flar2.homebutton.MapperService"
    private val zank = "zank.remote/zank.remote.RemoteService"
    private val loaded = Predicate<String> { true }
    private val notLoaded = Predicate<String> { false }

    private fun kept(vararg services: String) = GuardRules.start(services.toList(), emptySet(), self)

    @Test fun firstRunKeepsWhatIsOnExceptHaven() {
        val s = GuardRules.start(null, setOf(mapper, haven), self)
        assertEquals(mapOf(mapper to Keep.ON), s.keep)
    }

    @Test fun theListFromEarlierVersionsCarriesOver() {
        val s = GuardRules.start(setOf(mapper, zank), setOf(mapper), self)
        assertEquals(mapOf(mapper to Keep.ON, zank to Keep.ON), s.keep)
    }

    @Test fun droppedAtBootIsRestoredWithoutAsking() {
        val r = GuardRules.decide(kept(mapper, zank), emptySet(), loaded, true, self)
        assertEquals(listOf(mapper, zank), r.restore)
        assertTrue(r.ask.isEmpty())
    }

    @Test fun appThatLoadsLateIsRestoredWithoutAsking() {
        // Drive not mounted yet: nothing to do, still kept on.
        var r = GuardRules.decide(kept(zank), emptySet(), notLoaded, true, self)
        assertTrue(r.restore.isEmpty())
        assertEquals(Keep.ON, r.state.keepOf(zank))
        // It loads after the TV has settled: it was dropped, not switched off, so no question.
        r = GuardRules.decide(r.state, emptySet(), loaded, false, self)
        assertEquals(listOf(zank), r.restore)
        assertTrue(r.ask.isEmpty())
    }

    @Test fun switchedOffOutsideHavenIsAskedAboutOnce() {
        var r = GuardRules.decide(kept(mapper), setOf(mapper), loaded, false, self)
        // Switched off in the TV's settings, app loaded, TV settled: ask rather than guess.
        r = GuardRules.decide(r.state, emptySet(), loaded, false, self)
        assertTrue(r.restore.isEmpty())
        assertEquals(listOf(mapper), r.ask)
        // While the question is open, later checks don't ask again.
        r = GuardRules.decide(r.state, emptySet(), loaded, false, self)
        assertTrue(r.ask.isEmpty())
        assertEquals(Keep.ON, r.state.keepOf(mapper))
    }

    @Test fun appUnmountedThenBackIsRestoredWithoutAsking() {
        var r = GuardRules.decide(kept(zank), setOf(zank), loaded, false, self)
        // The drive drops out and Android drops the service with it.
        r = GuardRules.decide(r.state, emptySet(), notLoaded, false, self)
        assertTrue(r.ask.isEmpty())
        r = GuardRules.decide(r.state, emptySet(), loaded, false, self)
        assertEquals(listOf(zank), r.restore)
        assertTrue(r.ask.isEmpty())
    }

    @Test fun switchedOnAnywhereIsKeptOn() {
        val off = GuardRules.setKeep(kept(mapper), zank, false)
        val r = GuardRules.decide(off, setOf(mapper, zank), loaded, false, self)
        assertEquals(Keep.ON, r.state.keepOf(zank))
    }

    @Test fun shortAndLongFormsAreTheSameService() {
        assertEquals(mapper, GuardRules.normal("flar2.homebutton/.MapperService"))
        assertEquals(zank, GuardRules.normal(zank))
        val r = GuardRules.decide(kept("flar2.homebutton/.MapperService"), setOf(mapper), loaded, false, self)
        assertTrue(r.restore.isEmpty() && r.ask.isEmpty())
        assertEquals(mapOf(mapper to Keep.ON), r.state.keep)
    }

    @Test fun havensOwnServiceIsLeftToTheHomeButtonTab() {
        val r = GuardRules.decide(GuardState.EMPTY, setOf(haven), loaded, false, self)
        assertTrue(r.state.keep.isEmpty())
        assertTrue(GuardRules.decide(r.state, emptySet(), loaded, false, self).ask.isEmpty())
    }
}
