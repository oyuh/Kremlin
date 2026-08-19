package me.lawsonhart.kremlin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the suggestion list does with what the admin has typed so far. The property that matters is
 * that anything offered is something {@link Tpa#pickPlayer} would then resolve -- a suggestion the
 * command turns around and rejects is worse than no suggestion.
 */
class TabCompleteTest {

    private static final List<String> ONLINE = List.of("~Dalton", "oyuh", "Steve", "steve_2");

    @Test
    void anEmptyArgumentOffersEverything() {
        assertEquals(List.of("oyuh", "Steve", "steve_2", "~Dalton"), TabComplete.filter("", ONLINE),
                "sorted case-insensitively; a nickname's prefix character sorts after the letters");
    }

    @Test
    void aPrefixedNicknameIsStillFoundByTypingTheName() {
        // Essentials puts a ~ on nicknames by default, so a literal prefix match alone would hide
        // the one name staff can actually see on screen.
        assertEquals(List.of("~Dalton"), TabComplete.filter("Dal", ONLINE));
        assertEquals(List.of("~Dalton"), TabComplete.filter("~Dal", ONLINE), "typing the prefix too");
        assertTrue(Tpa.pickPlayer("~Dalton", List.of("oyuh"), List.of("~Dalton")) == 0,
                "and what we offered resolves back to that player");
    }

    @Test
    void matchingIsCaseInsensitiveAndDeduplicated() {
        assertEquals(List.of("Steve", "steve_2"), TabComplete.filter("STE", ONLINE));
        assertEquals(List.of("Steve"), TabComplete.filter("steve", List.of("Steve", "Steve", "steve")),
                "one online player and one stored snapshot of them is still one suggestion");
    }

    @Test
    void nothingMatchingMeansNothingOffered() {
        assertEquals(List.of(), TabComplete.filter("zzz", ONLINE));
        assertEquals(List.of(), TabComplete.filter("x", List.of("", "   ")), "blank names are not names");
    }
}
