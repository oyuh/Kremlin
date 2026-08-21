package me.lawsonhart.kremlin.discord;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The link code rules that do not need a server to check.
 *
 * The redemption flow itself needs a {@code Users} store, which needs a plugin, so what is
 * pinned here is the alphabet and the normalising -- the two things a player interacts with
 * directly, and the two that silently waste everybody's time when wrong.
 */
class LinksTest {

    /**
     * A code is read off one screen and typed into another, often a phone. Characters that look
     * like each other in the game font turn "it says it's wrong" into a support conversation.
     */
    @Test
    void theAlphabetHasNoLookalikes() {
        for (final char c : "O0I1S5".toCharArray()) {
            assertFalse(Links.ALPHABET.indexOf(c) >= 0,
                    "'" + c + "' is easily confused with another character in the alphabet");
        }
    }

    @Test
    void theAlphabetIsUpperCaseAndUnambiguous() {
        assertEquals(Links.ALPHABET.toUpperCase(Locale.ROOT), Links.ALPHABET,
                "codes are shown and compared upper case");
        assertEquals(Links.ALPHABET.length(),
                Links.ALPHABET.chars().distinct().count(), "a repeated character skews nothing but is a mistake");
        assertTrue(Links.ALPHABET.length() >= 24,
                "too small an alphabet makes a 6-character code guessable");
    }

    /** Everything the alphabet can produce has to survive being normalised. */
    @Test
    void normalisingKeepsEveryCharacterACodeCanContain() {
        assertEquals(Links.ALPHABET, Links.normalise(Links.ALPHABET));
    }

    @Test
    void normalisingAbsorbsHowPeopleActuallyTypeACode() {
        assertEquals("ABC234", Links.normalise("abc234"), "typed lower case");
        assertEquals("ABC234", Links.normalise("  ABC234  "), "pasted with whitespace");
        assertEquals("ABC234", Links.normalise("ABC-234"), "read out with a dash");
        assertEquals("ABC234", Links.normalise("ABC 234"), "read out with a space");
    }

    @Test
    void nothingUsableNormalisesToNothing() {
        assertEquals("", Links.normalise("---"));
        assertEquals("", Links.normalise("   "));
    }

    /**
     * A code only ever redeems on the far side. Redeeming your own would link you to yourself
     * and prove nothing about the account at the other end -- which is the entire point.
     */
    @Test
    void aPendingCodeKnowsWhichSideMadeIt() {
        Links.Pending fromGame = new Links.Pending(java.util.UUID.randomUUID(), 0L, Long.MAX_VALUE);
        Links.Pending fromDiscord = new Links.Pending(null, 1234567890L, Long.MAX_VALUE);

        assertTrue(fromGame.fromGame());
        assertFalse(fromDiscord.fromGame());
    }

    @Test
    void expiryIsCheckedAgainstTheClockPassedIn() {
        long now = System.currentTimeMillis();
        Links.Pending soon = new Links.Pending(null, 1L, now + 1000L);

        assertFalse(soon.expired(now));
        assertTrue(soon.expired(now + 1001L));
        assertTrue(soon.expired(soon.expires()), "the moment it expires counts as expired");
    }
}
