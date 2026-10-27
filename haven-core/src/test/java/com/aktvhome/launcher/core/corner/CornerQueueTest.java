package com.aktvhome.launcher.core.corner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CornerQueueTest {

    @Test
    public void oneToastAtATime() {
        CornerQueue q = new CornerQueue();
        assertTrue(q.tryStart(CornerKind.RECAP));
        assertEquals(CornerKind.RECAP, q.getActive());
        // Even a more urgent one waits for the one showing to close.
        assertFalse(q.tryStart(CornerKind.NBA_ALERT));
        q.finish(CornerKind.RECAP);
        assertTrue(q.tryStart(CornerKind.NBA_ALERT));
    }

    @Test
    public void theMostUrgentWaitingGoesFirst() {
        // The TV starts during a close game: an alert is waiting while the recap and a reminder are ready.
        CornerQueue q = new CornerQueue();
        q.want(CornerKind.NBA_ALERT);
        q.want(CornerKind.RECAP);
        assertFalse(q.tryStart(CornerKind.REMINDER));
        assertFalse(q.tryStart(CornerKind.RECAP));
        assertTrue(q.tryStart(CornerKind.NBA_ALERT));
        q.finish(CornerKind.NBA_ALERT);
        assertFalse(q.tryStart(CornerKind.REMINDER));
        assertTrue(q.tryStart(CornerKind.RECAP));
        q.finish(CornerKind.RECAP);
        assertTrue(q.tryStart(CornerKind.REMINDER));
    }

    @Test
    public void aSwitchedOffQuestionGoesBeforeWaitingAlerts() {
        CornerQueue q = new CornerQueue();
        q.want(CornerKind.NBA_ALERT);
        q.want(CornerKind.SWITCHED_OFF);
        assertFalse(q.tryStart(CornerKind.NBA_ALERT));
        assertTrue(q.tryStart(CornerKind.SWITCHED_OFF));
    }

    @Test
    public void startingStopsWaitingAndWithdrawingFreesTheWay() {
        CornerQueue q = new CornerQueue();
        q.want(CornerKind.NBA_ALERT);
        q.want(CornerKind.NBA_ALERT);
        assertTrue(q.tryStart(CornerKind.NBA_ALERT));
        assertEquals(1, q.waiting(CornerKind.NBA_ALERT));
        q.finish(CornerKind.NBA_ALERT);
        // The second alert was dropped (Home pressed): the recap no longer waits for it.
        q.withdraw(CornerKind.NBA_ALERT);
        assertTrue(q.tryStart(CornerKind.RECAP));
        q.withdraw(CornerKind.NBA_ALERT);
        assertEquals(0, q.waiting(CornerKind.NBA_ALERT));
    }

    @Test
    public void finishingSomethingElseChangesNothing() {
        CornerQueue q = new CornerQueue();
        assertTrue(q.tryStart(CornerKind.RECAP));
        q.finish(CornerKind.REMINDER);
        assertEquals(CornerKind.RECAP, q.getActive());
        q.finish(CornerKind.RECAP);
        assertNull(q.getActive());
    }
}
