package me.lawsonhart.kremlin.punish;

import me.lawsonhart.kremlin.core.Durations;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mute expiry arithmetic. Getting the sentinel wrong here either lets a permanent mute lapse the
 * moment it is read back, or leaves an expired one in place forever.
 */
class PunishmentsTest {

    private static final UUID WHO = UUID.randomUUID();

    private static Punishments.Entry mute(final long until) {
        return new Punishments.Entry("mute", WHO, "spam", "Lawson", System.currentTimeMillis(), until);
    }

    @Test
    void aMuteWithTimeLeftIsStillOn() {
        Punishments.Entry entry = mute(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));

        assertTrue(entry.active());
        long left = entry.remaining();
        assertTrue(left > TimeUnit.MINUTES.toMillis(59) && left <= TimeUnit.HOURS.toMillis(1),
                "got " + left);
    }

    @Test
    void aMuteWhoseTimeHasPassedIsOff() {
        Punishments.Entry entry = mute(System.currentTimeMillis() - 1L);

        assertFalse(entry.active());
        assertEquals(0L, entry.remaining(), "never negative, or it reads as a fresh mute");
    }

    /**
     * PERMANENT is -1, which is also "in the distant past" if compared as a timestamp. A
     * permanent mute must not read as expired.
     */
    @Test
    void aPermanentMuteNeverExpires() {
        Punishments.Entry entry = mute(Durations.PERMANENT);

        assertTrue(entry.active(), "-1 is the forever sentinel, not a timestamp from 1969");
        assertEquals(Durations.PERMANENT, entry.remaining());
        assertEquals("forever", Durations.describe(entry.remaining()));
    }

    /** A kick has no duration at all, and must never read as an ongoing punishment. */
    @Test
    void aOneOffEntryIsNotActive() {
        Punishments.Entry kick = new Punishments.Entry("kick", WHO, "rules", "Lawson",
                System.currentTimeMillis(), 0L);

        assertFalse(kick.active());
        assertEquals(0L, kick.remaining());
    }

    /**
     * History rows come back out of YAML as untyped maps, and a row written by an older version
     * may simply be missing a key -- which must read as a default, not throw mid-load.
     */
    @Test
    void aHistoryRowMissingKeysStillReads() {
        Map<?, ?> full = Map.of("type", "ban", "by", "Lawson", "reason", "griefing");
        assertEquals("ban", Punishments.str(full, "type", "?"));
        assertEquals("griefing", Punishments.str(full, "reason", ""));

        Map<?, ?> sparse = Map.of("type", "kick");
        assertEquals("?", Punishments.str(sparse, "by", "?"), "a missing key falls back");
        assertEquals("", Punishments.str(sparse, "reason", ""));
    }
}
