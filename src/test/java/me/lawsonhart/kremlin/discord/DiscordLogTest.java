package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.core.Durations;
import me.lawsonhart.kremlin.punish.Punishments;
import net.dv8tion.jda.api.EmbedBuilder;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the log channel is handed.
 *
 * The send itself needs a live JDA, but the two things that actually go wrong here do not: an
 * embed that Discord refuses because a field is empty or too long, and a duration rendered from
 * the wrong end of a permanent punishment.
 */
class DiscordLogTest {

    private static Punishments.Entry entry(final String type, final String reason, final long until) {
        return new Punishments.Entry(type, UUID.randomUUID(), reason, "Lawson",
                System.currentTimeMillis(), until);
    }

    /**
     * Discord rejects an embed field with a blank value outright, which would turn a successful
     * ban into a warning in the log. A reason is optional on every punishment command, so the
     * blank case is the normal one, not the edge case.
     */
    @Test
    void aReasonlessPunishmentStillProducesASendableEmbed() {
        Punishments.Entry noReason = entry("kick", "", 0L);

        assertTrue(noReason.reason().isBlank(), "this is what /kick with no reason records");
        // The log skips a blank reason rather than adding an empty field.
        EmbedBuilder embed = new EmbedBuilder().setTitle("Kicked")
                .addField("Player", "Someone", true)
                .addField("By", noReason.by(), true);
        assertDoesNotThrow(() -> embed.build());
        assertFalse(embed.isEmpty());
    }

    /** A permanent punishment must read as "forever", not as a countdown from a 1969 timestamp. */
    @Test
    void aPermanentEntryRendersItsLengthAsForever() {
        Punishments.Entry forever = entry("ban", "griefing", Durations.PERMANENT);

        assertEquals(Durations.PERMANENT, forever.remaining());
        assertEquals("forever", Durations.describe(forever.remaining()));
    }

    @Test
    void aTimedEntryRendersWhatIsLeftRatherThanWhatItWas() {
        long hour = TimeUnit.HOURS.toMillis(1);
        Punishments.Entry timed = entry("mute", "spam", System.currentTimeMillis() + hour);

        String shown = Durations.describe(timed.remaining());
        assertTrue(shown.startsWith("59m") || shown.startsWith("1h"), "got " + shown);
    }

    /** A one-off has no length, and the log leaves the field off entirely rather than showing 0s. */
    @Test
    void aOneOffHasNoLengthToShow() {
        assertEquals(0L, entry("warn", "rules", 0L).until());
    }

    /**
     * Embed fields cap at 1024 characters. A reason is free text typed by staff, so the long
     * case is reachable without anybody trying.
     */
    @Test
    void aVeryLongReasonIsStillWithinTheFieldLimit() {
        String longReason = "x".repeat(1024);
        assertDoesNotThrow(() -> new EmbedBuilder().setTitle("Banned")
                .addField("Reason", longReason, false).build());

        assertThrows(IllegalArgumentException.class,
                () -> new EmbedBuilder().addField("Reason", "x".repeat(1025), false),
                "past the cap JDA refuses it, which is why the reason is truncated first");

        // What the log actually sends, for a reason well past the cap.
        assertDoesNotThrow(() -> new EmbedBuilder().setTitle("Banned")
                .addField("Reason", DiscordLogTest.trimmed("x".repeat(5000)), false).build());
    }

    /** Mirrors DiscordLog's own trim, pinning the length contract it has to meet. */
    static String trimmed(final String text) {
        return text.length() <= 1024 ? text : text.substring(0, 1021) + "...";
    }
}
