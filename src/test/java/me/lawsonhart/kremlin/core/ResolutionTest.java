package me.lawsonhart.kremlin.core;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding a player by the name staff can actually see.
 *
 * A nickname is what appears on screen, so it is what gets typed into /history and /ban. Matching
 * it means comparing the *rendered, stripped* form -- the stored value is MiniMessage, and
 * nothing sensible is ever typed as "&lt;gradient:#55FFFF:#FF55FF&gt;Lawson".
 */
class ResolutionTest {

    private static String rendered(final String miniMessage) {
        return PlainTextComponentSerializer.plainText()
                .serialize(MiniMessage.miniMessage().deserialize(miniMessage)).trim();
    }

    /** The comparison both the online and offline paths make. */
    private static boolean matches(final String storedNick, final String typed) {
        return Names.bare(rendered(storedNick)).equals(Names.bare(typed));
    }

    @Test
    void aGradientNicknameIsFoundByTypingWhatItReads() {
        String stored = "<gradient:#55FFFF:#FF55FF><bold>Dalton</bold></gradient>";

        assertEquals("Dalton", rendered(stored), "this is what is on screen");
        assertTrue(matches(stored, "Dalton"));
        assertTrue(matches(stored, "dalton"), "case does not matter");
        assertFalse(matches(stored, stored), "nobody types the MiniMessage");
    }

    /** A nickname carrying a prefix is still reached by the name inside it. */
    @Test
    void aPrefixedNicknameIsFoundByTheNameItself() {
        assertTrue(matches("<red>~Dalton", "Dalton"));
        assertTrue(matches("<red>~Dalton", "~Dalton"), "and by what is literally shown");
    }

    @Test
    void aDifferentNameDoesNotMatch() {
        assertFalse(matches("<red>Dalton", "Steve"));
        assertFalse(matches("<red>Dalton", ""));
    }

    /**
     * Offline completion has to offer the same names the lookup accepts, or staff are told a
     * player does not exist by the very list that suggested them.
     */
    @Test
    void filterOffersOfflineNamesTheSameWayAsOnlineOnes() {
        List<String> everyone = List.of("Lawson", "oyuh", "~Dalton", "SteveOffline");

        assertEquals(List.of("SteveOffline"), Names.filter("steve", everyone),
                "an offline name completes case-insensitively like any other");
        assertTrue(Names.filter("dalton", everyone).contains("~Dalton"),
                "and a prefixed nickname is reached by the bare name");
        assertTrue(Names.filter("", everyone).containsAll(everyone), "no input offers everyone");
    }

    /** Nothing usable can never match somebody, or an empty argument would hit the first player. */
    @Test
    void anEmptyQueryMatchesNobody() {
        assertEquals("", Names.bare("***"));
        assertFalse(matches("<red>Dalton", "***"), "punctuation-only strips to nothing");
    }
}
