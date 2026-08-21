package me.lawsonhart.kremlin.teleport;

import me.lawsonhart.kremlin.core.Names;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Home naming and tpa name resolution. Split out of CombatTest when the packages were separated. */
class TeleportTest {

    /**
     * The team-home commands live in SimpleTeams, not here, so this matcher is the only thing
     * standing between /hq and an instant teleport with no warmup.
     */
    @Test
    void teamHomeCommandsAreRecognisedInEveryForm() {
        assertTrue(Homes.isTeamHome("/hq"));
        assertTrue(Homes.isTeamHome("/TeamHQ"));
        assertTrue(Homes.isTeamHome("/teamhome"));
        assertTrue(Homes.isTeamHome("/team home"));
        assertTrue(Homes.isTeamHome("/simpleteams:team Home"));
        assertFalse(Homes.isTeamHome("/team sethome"));
        assertFalse(Homes.isTeamHome("/team"));
        assertFalse(Homes.isTeamHome("/home"));
        assertFalse(Homes.isTeamHome("/sethq"));
    }

    @Test
    void tpaResolvesRealNamesAndEssentialsNicknames() {
        // index:            0            1          2
        List<String> names = List.of("Steve", "Alexandra", "Bobby");
        List<String> nicks = List.of("~Bob", "Alexandra", "Bobby");

        assertEquals(0, Names.pickPlayer("Steve", names, nicks), "real name");
        assertEquals(0, Names.pickPlayer("steve", names, nicks), "case insensitive");
        assertEquals(0, Names.pickPlayer("Bob", names, nicks), "nickname, prefix char ignored");
        assertEquals(0, Names.pickPlayer("~Bob", names, nicks), "nickname typed with its prefix");
        assertEquals(1, Names.pickPlayer("Alex", names, nicks), "prefix of a real name");
        assertEquals(-1, Names.pickPlayer("Nobody", names, nicks));
        assertEquals(-1, Names.pickPlayer("", names, nicks));
        assertEquals(-1, Names.pickPlayer(null, names, nicks));
    }

    /**
     * A real name must always win, or someone could nickname themselves after another player
     * and quietly intercept their teleport requests.
     */
    @Test
    void aNicknameCannotHijackSomeoneElsesRealName() {
        List<String> names = List.of("Impostor", "Steve");
        List<String> nicks = List.of("Steve", "Steve");

        assertEquals(1, Names.pickPlayer("Steve", names, nicks), "the actual Steve, not the impostor");
    }

    /**
     * Home names are normalised on both the command path and the Essentials import path, so a
     * mismatch between the two would silently duplicate or hide imported homes.
     */
    @Test
    void homeNamesNormaliseTheSameWayEverywhere() {
        assertEquals("base", Homes.clean("Base"));
        assertEquals("base", Homes.clean("  BASE  "));
        assertEquals("my-home_2", Homes.clean("My-Home_2"));
        assertEquals("nether", Homes.clean("nether!!"), "punctuation dropped, not rejected");
        assertEquals("", Homes.clean("***"), "nothing usable left");
        assertEquals("", Homes.clean(null));
        // Reserved names collide with the bed/hq slots, so setHome refuses them by value.
        assertEquals(Homes.BED, Homes.clean("Bed"));
        assertEquals(Homes.HQ, Homes.clean("HQ"));
    }

    @Test
    void theHubNameIsReservedAlongsideBedAndHq() {
        assertTrue(Homes.reserved("hub"));
        assertTrue(Homes.reserved("HUB"), "a home called HUB would shadow the community hub button");
        assertTrue(Homes.reserved(Homes.BED));
        assertTrue(Homes.reserved(Homes.HQ));
        assertFalse(Homes.reserved("base"));
    }

    @Test
    void tpaGuiPagingHandlesEmptyExactAndWrapAround() {
        assertEquals(1, Tpa.pageCount(0, 36), "empty list still renders one page");
        assertEquals(1, Tpa.pageCount(36, 36), "exactly full is one page, not two");
        assertEquals(2, Tpa.pageCount(37, 36));

        assertEquals(0, Tpa.wrapPage(0, 1));
        assertEquals(1, Tpa.wrapPage(-1, 2), "prev from page 0 wraps to the last page");
        assertEquals(0, Tpa.wrapPage(2, 2), "next past the end wraps to the first");
        assertEquals(0, Tpa.wrapPage(-3, 1), "single page absorbs any index");
    }
}
