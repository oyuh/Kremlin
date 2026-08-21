package me.lawsonhart.kremlin.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Durations decide how long somebody is banned for, so every misreading here is either a ban
 * that quietly does nothing or one that lasts far longer than was typed.
 */
class DurationsTest {

    private static long minutes(final long n) {
        return TimeUnit.MINUTES.toMillis(n);
    }

    private static long days(final long n) {
        return TimeUnit.DAYS.toMillis(n);
    }

    @Test
    void singleUnitsReadAsThemselves() {
        assertEquals(TimeUnit.SECONDS.toMillis(30), Durations.millis("30s"));
        assertEquals(minutes(30), Durations.millis("30m"));
        assertEquals(TimeUnit.HOURS.toMillis(6), Durations.millis("6h"));
        assertEquals(days(7), Durations.millis("7d"));
        assertEquals(days(7), Durations.millis("1w"));
        assertEquals(days(365), Durations.millis("1y"));
    }

    /**
     * The trap in this format: m is minutes, mo is months. Matching shortest-first would read
     * "1mo" as one minute and leave a stray "o" -- a month-long ban lasting sixty seconds.
     */
    @Test
    void monthsAreNotMinutes() {
        assertEquals(days(30), Durations.millis("1mo"));
        assertEquals(minutes(1), Durations.millis("1m"));
        assertNotEquals(Durations.millis("1m"), Durations.millis("1mo"));
        assertEquals(days(60), Durations.millis("2mo"));
    }

    @Test
    void partsAddUpInAnyOrder() {
        assertEquals(days(2) + TimeUnit.HOURS.toMillis(4), Durations.millis("2d4h"));
        assertEquals(days(2) + TimeUnit.HOURS.toMillis(4), Durations.millis("2d 4h"), "spaces are fine");
        assertEquals(minutes(90), Durations.millis("1h30m"));
        assertEquals(days(1) + minutes(1) + 1000L, Durations.millis("1d1m1s"));
    }

    @Test
    void caseDoesNotMatter() {
        assertEquals(days(1), Durations.millis("1D"));
        assertEquals(days(30), Durations.millis("1MO"));
    }

    @Test
    void foreverIsItsOwnAnswer() {
        assertEquals(Durations.PERMANENT, Durations.millis("perm"));
        assertEquals(Durations.PERMANENT, Durations.millis("permanent"));
        assertEquals(Durations.PERMANENT, Durations.millis("forever"));
        assertEquals(Durations.PERMANENT, Durations.millis("-1"));
        assertEquals(Durations.PERMANENT, Durations.millis("  FOREVER  "));
    }

    /**
     * Anything unreadable has to come back as INVALID rather than as zero. A zero-length ban
     * would be applied and expire instantly, which reads to staff as "the command did nothing".
     */
    @Test
    void nonsenseIsRefusedRatherThanTreatedAsZero() {
        assertEquals(Durations.INVALID, Durations.millis("soon"));
        assertEquals(Durations.INVALID, Durations.millis(""));
        assertEquals(Durations.INVALID, Durations.millis(null));
        assertEquals(Durations.INVALID, Durations.millis("10"), "a number with no unit is ambiguous");
        assertEquals(Durations.INVALID, Durations.millis("10x"), "x is not a unit");
        assertEquals(Durations.INVALID, Durations.millis("1d junk"), "trailing scrap invalidates it");
        assertEquals(Durations.INVALID, Durations.millis("junk 1d"), "and so does leading scrap");
        assertEquals(Durations.INVALID, Durations.millis("0s"), "zero is not a duration");
    }

    /** A number too large to hold is a request for forever, not an overflow into the past. */
    @Test
    void anAbsurdlyLargeNumberDoesNotOverflow() {
        assertEquals(Durations.PERMANENT, Durations.millis("999999999999y"));
        assertTrue(Durations.millis("100y") > 0, "but a merely long one still reads as long");
    }

    /**
     * max-mute-time / max-tempban-time are -1 for "no limit", which is the same sentinel as
     * "forever" -- so a plain comparison would cap every punishment at already-expired.
     */
    @Test
    void aCapOfMinusOneMeansNoLimitRatherThanNoTime() {
        assertTrue(Durations.within(days(365), Durations.PERMANENT), "-1 caps nothing");
        assertTrue(Durations.within(Durations.PERMANENT, Durations.PERMANENT), "even forever");

        assertTrue(Durations.within(minutes(30), TimeUnit.HOURS.toMillis(1)), "inside a real cap");
        assertTrue(Durations.within(minutes(60), TimeUnit.HOURS.toMillis(1)), "exactly the cap is allowed");
        assertFalse(Durations.within(days(2), TimeUnit.HOURS.toMillis(1)), "past a real cap");
        assertFalse(Durations.within(Durations.PERMANENT, TimeUnit.HOURS.toMillis(1)),
                "forever must never slip past a real cap");
    }

    @Test
    void describeReadsBackTheWayItWasTyped() {
        assertEquals("forever", Durations.describe(Durations.PERMANENT));
        assertEquals("30m", Durations.describe(minutes(30)));
        assertEquals("2d 4h", Durations.describe(days(2) + TimeUnit.HOURS.toMillis(4)));
        assertEquals("7d", Durations.describe(days(7)), "a round number keeps one unit");
        assertEquals("0s", Durations.describe(0));
        assertEquals("0s", Durations.describe(-5), "already expired reads as nothing left");
    }
}
