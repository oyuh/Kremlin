package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.core.Durations;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Playtime comes from vanilla's PLAY_ONE_MINUTE statistic, which counts <em>ticks</em> despite
 * the name. Reading it as minutes would report 3 hours as 7 seconds.
 */
class PlaytimeTest {

    /** Mirrors Lookup.playedMillis: ticks to millis at 20 per second. */
    private static long millis(final int ticks) {
        return Math.max(0L, (long) ticks) * 50L;
    }

    @Test
    void theStatisticIsTicksNotMinutes() {
        assertEquals(TimeUnit.SECONDS.toMillis(1), millis(20), "20 ticks is one second");
        assertEquals(TimeUnit.MINUTES.toMillis(1), millis(20 * 60));
        assertEquals(TimeUnit.HOURS.toMillis(1), millis(20 * 60 * 60));
    }

    @Test
    void aRealPlaytimeReadsBackSensibly() {
        long threeHours = millis(20 * 60 * 60 * 3);
        assertEquals("3h", Durations.describe(threeHours));

        long twoDaysFour = millis(20 * 60 * 60 * 52);
        assertEquals("2d 4h", Durations.describe(twoDaysFour));
    }

    /** A never-joined player has no statistics, and must not read as a negative or a huge number. */
    @Test
    void noStatisticsReadsAsNothing() {
        assertEquals(0L, millis(0));
        assertEquals(0L, millis(-5), "a negative statistic is clamped, not wrapped");
    }

    /**
     * The counter is an int of ticks. It only overflows after about three and a half years of
     * continuous play, but the multiplication must happen in long arithmetic regardless -- doing
     * it in int would wrap far sooner.
     */
    @Test
    void aVeryLargeCounterDoesNotOverflow() {
        long huge = millis(Integer.MAX_VALUE);
        assertTrue(huge > 0, "must not wrap negative: " + huge);
        assertEquals(2_147_483_647L * 50L, huge);
    }
}
