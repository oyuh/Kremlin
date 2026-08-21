package me.lawsonhart.kremlin.combat;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.HashMap;
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
     * Regression from taking the labels over properly: these commands used to be cancelled at
     * LOWEST priority so they could beat Essentials to the label, which meant the combat block
     * listener never saw them and blocked-commands never applied. Each one checks combat itself,
     * where it matters -- at the teleport, not at the keystroke -- so denying a request or
     * setting a home has to keep working mid-fight, whatever the config lists.
     */
    @Test
    void commandsWeOwnAreGatedByThemselvesRatherThanTheBlockList() {
        assertTrue(Combat.ours("tpdeny"), "declining a request is not an escape");
        assertTrue(Combat.ours("tpno"), "and neither is its alias");
        assertTrue(Combat.ours("tpaccept"), "accepting checks combat when it teleports you");
        assertTrue(Combat.ours("tpacancel"));
        assertTrue(Combat.ours("sethome"), "storing a location moves nobody");
        assertTrue(Combat.ours("homes"), "opening the menu is not the teleport");
        assertTrue(Combat.ours("home"));
        assertTrue(Combat.ours("enderchest"));
        assertTrue(Combat.ours("ec"), "reached by every alias, not just the declared name");

        assertFalse(Combat.ours("spawn"), "somebody else's command, so the block list decides");
        assertFalse(Combat.ours("sethq"), "team HQ management stays blocked outright");
    }

    /**
     * The same rule, for the labels taken over with the teleport suite and warps. Each of these
     * is on the shipped blocked-commands list, so without the exemption owning them would start
     * refusing things that are not escapes at all -- listing the warps, or turning your own
     * teleport requests off.
     */
    @Test
    void theTeleportSuiteAndWarpsGateThemselvesToo() {
        assertTrue(Combat.ours("warp"), "the teleport is gated; naming a warp is not");
        assertTrue(Combat.ours("warps"), "listing them moves nobody");
        assertTrue(Combat.ours("tptoggle"), "changing a preference is not an escape");
        assertTrue(Combat.ours("back"), "gated where it teleports, and warmed up like /home");
        assertTrue(Combat.ours("top"));
        assertTrue(Combat.ours("tp"));
        assertTrue(Combat.ours("tpall"), "gated per player, so one fight cannot be pulled apart");
        assertTrue(Combat.ours("return"), "reached by every alias, not just the declared name");
        assertTrue(Combat.ours("s"), "/tphere's alias");

        assertFalse(Combat.ours("rtp"), "somebody else's random teleport is still blocked");
        assertFalse(Combat.ours("trash"), "/trash does not gate itself, so the list decides");
        assertFalse(Combat.ours("heal"), "nor does /heal -- no patching up mid-fight");
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
