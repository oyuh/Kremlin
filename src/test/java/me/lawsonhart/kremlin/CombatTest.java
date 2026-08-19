package me.lawsonhart.kremlin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** CombatPrev's own regression tests, carried over with the code they pin. */
class CombatTest {

    @Test
    void rootCommandStripsSlashNamespaceCaseAndArgs() {
        assertEquals("home", Combat.rootCommand("/essentials:Home myBase"));
        assertEquals("tpa", Combat.rootCommand("/TPA Steve"));
        assertEquals("spawn", Combat.rootCommand("/spawn"));
        assertEquals("back", Combat.rootCommand("back"));
    }

    /**
     * The namespaced form is the bypass that matters: if /essentials:tpa didn't normalise to
     * "tpa", players could route around our tpa system and reach Essentials' directly.
     */
    @Test
    void namespacedCommandsCannotDodgeTheTakeover() {
        assertEquals("tpa", Combat.rootCommand("/essentials:tpa Bob"));
        assertEquals("tpahere", Combat.rootCommand("/TPAHERE Bob"));
        assertEquals("tpaccept", Combat.rootCommand("/eSSentials:tpaccept"));
        assertEquals("tpdeny", Combat.rootCommand("/essentials:TpDeny Bob"));
        assertEquals("sethome", Combat.rootCommand("/essentials:SetHome base"));
        assertEquals("tpa", Combat.rootCommand("/minecraft:tpa"));
    }

    /**
     * Team home management hides behind /team, so the block list has to see the subcommand --
     * matching only the root would let a tagged player move their HQ and summon the team.
     */
    @Test
    void subcommandsAreMatchedSoTeamSethomeCanBeBlocked() {
        assertEquals("team sethome", Combat.rootAndSub("/team sethome"));
        assertEquals("team sethome", Combat.rootAndSub("/TEAM SetHome"), "case insensitive");
        assertEquals("team sethome", Combat.rootAndSub("/simpleteams:team sethome"), "namespace stripped");
        assertEquals("team sethome", Combat.rootAndSub("  /team   sethome  "), "ragged spacing");
        assertEquals("team delhome", Combat.rootAndSub("/team delhome"));
        assertEquals("team home", Combat.rootAndSub("/team home"), "the teleport is gated by the event, not here");

        assertNull(Combat.rootAndSub("/team"), "no subcommand typed");
        assertNull(Combat.rootAndSub("/sethq"), "single word is the root check's job");
    }

    /**
     * Regression: an upgraded jar leaves the old config.yml on disk, so keys added later read
     * back as "" -- which used to mean commands were cancelled with an empty message, i.e.
     * Essentials appearing to be dead with no explanation.
     */
    @Test
    void keysMissingFromAnOldConfigFallBackToTheBundledOne() {
        YamlConfiguration user = YamlConfiguration.loadConfiguration(
                new StringReader("messages:\n  deny-combat: \"mine\"\n"));
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                new StringReader("messages:\n  deny-combat: \"shipped\"\n  hq-unset: \"no hq\"\n"));

        assertNull(user.getString("messages.hq-unset"), "the bug: key simply isn't there");

        user.setDefaults(bundled);
        assertEquals("no hq", user.getString("messages.hq-unset"), "the fix");
        assertEquals("mine", user.getString("messages.deny-combat"), "user's own value still wins");

        // The trap that made the first attempt at this fix useless: an explicit default is
        // returned as-is and never consults the defaults layer. msg() must not pass one.
        assertEquals("", user.getString("messages.hq-unset", ""),
                "getString(path, def) bypasses defaults -- msg() must call getString(path)");
    }

    /**
     * The flee check decides whether a teleport costs 3s or 10s, so the threshold and the
     * cross-world rule both matter. 100 blocks -> distanceSq of 10000.
     */
    @Test
    void fleeCheckMeasuresDistanceFromTheFight() {
        double sq = 100 * 100;

        assertFalse(Combat.fled("world", 0, 64, 0, "world", 0, 64, 0, sq), "stood still");
        assertFalse(Combat.fled("world", 0, 64, 0, "world", 99, 64, 0, sq), "99 blocks is not fleeing");
        assertTrue(Combat.fled("world", 0, 64, 0, "world", 100, 64, 0, sq), "exactly 100 counts");
        assertTrue(Combat.fled("world", 0, 64, 0, "world", 500, 64, -300, sq), "well clear");

        // Diagonal: 60/80/0 is exactly 100 away, so it must count too.
        assertTrue(Combat.fled("world", 0, 0, 0, "world", 60, 80, 0, sq), "diagonal 3-4-5");
        assertFalse(Combat.fled("world", 0, 0, 0, "world", 60, 79, 0, sq), "just inside");

        assertTrue(Combat.fled("world", 0, 64, 0, "world_nether", 1, 64, 1, sq),
                "changing world always counts, however short the hop");
    }

    @Test
    void tpaResolvesRealNamesAndEssentialsNicknames() {
        // index:            0            1          2
        List<String> names = List.of("Steve", "Alexandra", "Bobby");
        List<String> nicks = List.of("~Bob", "Alexandra", "Bobby");

        assertEquals(0, Tpa.pickPlayer("Steve", names, nicks), "real name");
        assertEquals(0, Tpa.pickPlayer("steve", names, nicks), "case insensitive");
        assertEquals(0, Tpa.pickPlayer("Bob", names, nicks), "nickname, prefix char ignored");
        assertEquals(0, Tpa.pickPlayer("~Bob", names, nicks), "nickname typed with its prefix");
        assertEquals(1, Tpa.pickPlayer("Alex", names, nicks), "prefix of a real name");
        assertEquals(-1, Tpa.pickPlayer("Nobody", names, nicks));
        assertEquals(-1, Tpa.pickPlayer("", names, nicks));
        assertEquals(-1, Tpa.pickPlayer(null, names, nicks));
    }

    /**
     * A real name must always win, or someone could nickname themselves after another player
     * and quietly intercept their teleport requests.
     */
    @Test
    void aNicknameCannotHijackSomeoneElsesRealName() {
        List<String> names = List.of("Impostor", "Steve");
        List<String> nicks = List.of("Steve", "Steve");

        assertEquals(1, Tpa.pickPlayer("Steve", names, nicks), "the actual Steve, not the impostor");
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
    void tpaGuiPagingHandlesEmptyExactAndWrapAround() {
        assertEquals(1, Tpa.pageCount(0, 36), "empty list still renders one page");
        assertEquals(1, Tpa.pageCount(36, 36), "exactly full is one page, not two");
        assertEquals(2, Tpa.pageCount(37, 36));

        assertEquals(0, Tpa.wrapPage(0, 1));
        assertEquals(1, Tpa.wrapPage(-1, 2), "prev from page 0 wraps to the last page");
        assertEquals(0, Tpa.wrapPage(2, 2), "next past the end wraps to the first");
        assertEquals(0, Tpa.wrapPage(-3, 1), "single page absorbs any index");
    }

    /** /slowchat takes a raw player-typed argument, so every rejection path matters. */
    @Test
    void slowChatParsesSecondsAndRefusesNonsense() {
        assertEquals(0L, ChatAdmin.parseSeconds("off"), "off means off");
        assertEquals(0L, ChatAdmin.parseSeconds("OFF"));
        assertEquals(0L, ChatAdmin.parseSeconds("none"));
        assertEquals(0L, ChatAdmin.parseSeconds("0"), "zero is also off");
        assertEquals(5L, ChatAdmin.parseSeconds("5"));
        assertEquals(3600L, ChatAdmin.parseSeconds("3600"), "the ceiling itself is allowed");

        assertEquals(-1L, ChatAdmin.parseSeconds("3601"), "past the ceiling is a typo, not a plan");
        assertEquals(-1L, ChatAdmin.parseSeconds("-5"), "negative would never let anyone speak");
        assertEquals(-1L, ChatAdmin.parseSeconds("fast"));
        assertEquals(-1L, ChatAdmin.parseSeconds(""));
        assertEquals(-1L, ChatAdmin.parseSeconds("5s"));
    }

    /** The egg goes to whoever tops this table, so the numbers under it have to be sane. */
    @Test
    void dragonSharesFormatCleanlyAndNeverDivideByZero() {
        assertEquals("128.5", DragonDamage.round(128.46));
        assertEquals("0.0", DragonDamage.round(0.0));
        assertEquals("200.0", DragonDamage.round(200.0), "a solo kill is the dragon full health bar");

        assertEquals("37.5%", DragonDamage.percent(75.0, 200.0));
        assertEquals("100.0%", DragonDamage.percent(200.0, 200.0), "one player did all of it");
        assertEquals("0%", DragonDamage.percent(5.0, 0.0), "nothing recorded must not blow up the table");
    }

    @Test
    void remainingCountsDownAndSelfEvicts() {
        Map<UUID, Long> m = new HashMap<>();
        UUID id = UUID.randomUUID();

        assertEquals(0L, Combat.remaining(m, id), "absent = not locked");

        m.put(id, System.currentTimeMillis() + 5000L);
        long left = Combat.remaining(m, id);
        assertTrue(left > 4000L && left <= 5000L, "got " + left);

        m.put(id, System.currentTimeMillis() - 1L);
        assertEquals(0L, Combat.remaining(m, id), "expired = not locked");
        assertFalse(m.containsKey(id), "expired entries must be evicted, not leaked");
    }
}
