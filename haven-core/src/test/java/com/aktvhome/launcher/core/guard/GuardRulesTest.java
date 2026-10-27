package com.aktvhome.launcher.core.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class GuardRulesTest {

    private static final String SELF = "com.aktvhome.launcher";
    private static final String MAPPER = "flar2.homebutton/flar2.homebutton.MapperService";
    private static final String ZANK = "zank.remote/zank.remote.RemoteService";
    private static final Set<String> NONE = Collections.emptySet();

    private static GuardState kept(String... services) {
        return GuardRules.start(Arrays.asList(services), NONE, SELF);
    }

    /** Kept on, then switched off in the TV's settings with the TV settled: an open question. */
    private static GuardState asking(String service) {
        GuardResult r = GuardRules.decide(kept(service), NONE, s -> true, false, SELF);
        assertEquals(Collections.singletonList(service), r.getAsk());
        return r.getState();
    }

    @Test
    public void turnItBackOnKeepsItOn() {
        GuardState s = GuardRules.answer(asking(MAPPER), MAPPER, Answer.TURN_ON);
        assertEquals(Keep.ON, s.keepOf(MAPPER));
        assertTrue(s.getAsking().isEmpty());
    }

    @Test
    public void leaveItOffEndsTheQuestions() {
        GuardState s = GuardRules.answer(asking(MAPPER), MAPPER, Answer.LEAVE_OFF);
        assertEquals(Keep.OFF, s.keepOf(MAPPER));
        // Not restored and not asked about, now or after a restart.
        GuardResult r = GuardRules.decide(GuardRules.newBoot(s), NONE, x -> true, true, SELF);
        assertTrue(r.getRestore().isEmpty());
        assertTrue(r.getAsk().isEmpty());
        assertTrue(GuardRules.reminders(r.getState(), NONE, x -> true, SELF).isEmpty());
    }

    @Test
    public void remindAsksAgainAfterTheRestartButIsntRestoredByItself() {
        GuardState s = GuardRules.answer(asking(MAPPER), MAPPER, Answer.REMIND);
        assertEquals(Keep.REMIND, s.keepOf(MAPPER));
        // Not reminded again this boot... once it's been reminded about.
        s = GuardRules.reminded(s, MAPPER);
        assertTrue(GuardRules.reminders(s, NONE, x -> true, SELF).isEmpty());
        // After a restart: not switched on by itself, but reminded about.
        s = GuardRules.newBoot(s);
        GuardResult r = GuardRules.decide(s, NONE, x -> true, true, SELF);
        assertTrue(r.getRestore().isEmpty());
        assertEquals(Collections.singletonList(MAPPER), GuardRules.reminders(r.getState(), NONE, x -> true, SELF));
    }

    @Test
    public void remindMeAfterRestartWaitsForTheRestart() {
        // Answered "Remind me after restart" to the question: no reminder until the TV restarts.
        GuardState s = GuardRules.answer(asking(MAPPER), MAPPER, Answer.REMIND);
        assertTrue(GuardRules.reminders(s, NONE, x -> true, SELF).isEmpty());
        // After the restart it's reminded about; answering "Remind me after restart" again ends it for this boot.
        s = GuardRules.newBoot(s);
        assertEquals(Collections.singletonList(MAPPER), GuardRules.reminders(s, NONE, x -> true, SELF));
        s = GuardRules.answer(s, MAPPER, Answer.REMIND);
        assertTrue(GuardRules.reminders(s, NONE, x -> true, SELF).isEmpty());
        assertEquals(Collections.singletonList(MAPPER), GuardRules.reminders(GuardRules.newBoot(s), NONE, x -> true, SELF));
    }

    @Test
    public void anUnansweredQuestionBecomesAReminderAtTheNextBoot() {
        GuardState s = GuardRules.newBoot(asking(MAPPER));
        assertEquals(Keep.REMIND, s.keepOf(MAPPER));
        assertTrue(s.getAsking().isEmpty());
    }

    @Test
    public void anIgnoredReminderComesBackEveryRestart() {
        GuardState s = GuardRules.answer(asking(MAPPER), MAPPER, Answer.REMIND);
        for (int boot = 0; boot < 3; boot++) {
            s = GuardRules.newBoot(s);
            assertEquals(Collections.singletonList(MAPPER), GuardRules.reminders(s, NONE, x -> true, SELF));
            s = GuardRules.reminded(s, MAPPER);
        }
    }

    @Test
    public void remindersWaitForTheirApp() {
        GuardState s = GuardRules.newBoot(GuardRules.answer(asking(ZANK), ZANK, Answer.REMIND));
        assertTrue(GuardRules.reminders(s, NONE, x -> false, SELF).isEmpty());
        assertEquals(Collections.singletonList(ZANK), GuardRules.reminders(s, NONE, x -> true, SELF));
    }

    @Test
    public void switchingItOnInTheTvsSettingsEndsAReminder() {
        GuardState s = GuardRules.answer(asking(MAPPER), MAPPER, Answer.REMIND);
        GuardResult r = GuardRules.decide(s, Collections.singleton(MAPPER), x -> true, false, SELF);
        assertEquals(Keep.ON, r.getState().keepOf(MAPPER));
        assertTrue(GuardRules.reminders(r.getState(), Collections.singleton(MAPPER), x -> true, SELF).isEmpty());
    }

    @Test
    public void theToggleDecidesAtOnce() {
        GuardState s = GuardRules.setKeep(asking(MAPPER), MAPPER, false);
        assertEquals(Keep.OFF, s.keepOf(MAPPER));
        assertTrue(s.getAsking().isEmpty());
        s = GuardRules.setKeep(s, MAPPER, true);
        assertEquals(Keep.ON, s.keepOf(MAPPER));
    }

    @Test
    public void statusForTheTab() {
        GuardState s = kept(MAPPER);
        assertEquals(ServiceStatus.KEPT_ON, GuardRules.status(s, MAPPER, Collections.singleton(MAPPER), true));
        assertEquals(ServiceStatus.WAITING_FOR_APP, GuardRules.status(s, MAPPER, NONE, false));
        assertEquals(ServiceStatus.TURNING_ON, GuardRules.status(s, MAPPER, NONE, true));
        assertEquals(ServiceStatus.SWITCHED_OFF_OUTSIDE, GuardRules.status(asking(MAPPER), MAPPER, NONE, true));
        assertEquals(ServiceStatus.REMIND_AFTER_RESTART,
            GuardRules.status(GuardRules.answer(asking(MAPPER), MAPPER, Answer.REMIND), MAPPER, NONE, true));
        assertEquals(ServiceStatus.OFF, GuardRules.status(s, ZANK, NONE, true));
        assertTrue(ServiceStatus.WAITING_FOR_APP.isToggled());
        assertFalse(ServiceStatus.SWITCHED_OFF_OUTSIDE.isToggled());
    }

    @Test
    public void settledOnceEveryKeptServiceIsLoadedAndOn() {
        GuardState s = kept(MAPPER, ZANK);
        assertFalse(GuardRules.settled(s, Collections.singleton(MAPPER), x -> true, SELF));
        assertFalse(GuardRules.settled(s, Arrays.asList(MAPPER, ZANK), x -> !x.equals(ZANK), SELF));
        assertTrue(GuardRules.settled(s, Arrays.asList(MAPPER, ZANK), x -> true, SELF));
        // Services left off don't hold it up.
        assertTrue(GuardRules.settled(GuardRules.setKeep(s, ZANK, false), Collections.singleton(MAPPER), x -> true, SELF));
    }

    @Test
    public void savingAndReadingBack() {
        Map<String, Keep> keep = new LinkedHashMap<>();
        keep.put(MAPPER, Keep.ON);
        keep.put(ZANK, Keep.REMIND);
        GuardState s = new GuardState(keep, Collections.singleton(ZANK), NONE, NONE);
        assertEquals(keep, GuardState.decodeKeep(s.encodeKeep()));
        assertEquals(Collections.singleton(ZANK), GuardState.decodeSet(GuardState.encodeSet(s.getDropped())));
        // Garbage or a state from a newer version is skipped, not fatal.
        assertEquals(Collections.singletonMap(MAPPER, Keep.ON), GuardState.decodeKeep(MAPPER + "=ON:junk:" + ZANK + "=LATER"));
        assertTrue(GuardState.decodeKeep(null).isEmpty());
        assertTrue(GuardState.decodeSet("").isEmpty());
    }

    @Test
    public void unknownServicesHaveNoToggle() {
        assertNull(kept(MAPPER).keepOf(ZANK));
        List<String> none = GuardRules.reminders(kept(MAPPER), NONE, x -> true, SELF);
        assertTrue(none.isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void stateIsReadOnly() {
        kept(MAPPER).getKeep().clear();
    }
}
