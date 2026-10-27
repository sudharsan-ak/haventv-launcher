package com.aktvhome.launcher.core.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AdvancedTest {

    @Test
    public void madeAttemptedNeedsExactlyTwoNumbers() {
        assertEquals(new MadeAttempted(5, 10), Advanced.madeAttempted(" 5 - 10 "));
        // Empty pieces count, as in Kotlin: "5-" is two parts with one empty, "5-10-2" three.
        assertNull(Advanced.madeAttempted("5-"));
        assertNull(Advanced.madeAttempted("-10"));
        assertNull(Advanced.madeAttempted("5-10-2"));
        assertNull(Advanced.madeAttempted("a-b"));
        assertNull(Advanced.madeAttempted(""));
    }

    @Test
    public void numberIsZeroForAnythingThatIsntOne() {
        assertEquals(35.2, Advanced.number(" 35.2 "), 0.0);
        assertEquals(7.0, Advanced.number("+7"), 0.0);
        assertEquals(0.0, Advanced.number(""), 0.0);
        assertEquals(0.0, Advanced.number("DNP"), 0.0);
        assertEquals(0.0, Advanced.number(null), 0.0);
    }

    @Test
    public void nullOnlyWhenTheDenominatorIsZero() {
        assertNull(Advanced.ratio(1, 0));
        assertEquals(0.0, Advanced.ratio(0, 4), 0.0);
        assertEquals("0.0", Advanced.show(0.0));
    }

    @Test
    public void madeAttemptedIsAValue() {
        MadeAttempted fg = new MadeAttempted(9, 18);
        assertEquals(9.0, fg.getMade(), 0.0);
        assertEquals(18.0, fg.component2(), 0.0);
        assertEquals(new MadeAttempted(9, 18).hashCode(), fg.hashCode());
        assertEquals(new MadeAttempted(0, 0), MadeAttempted.NONE);
    }
}
