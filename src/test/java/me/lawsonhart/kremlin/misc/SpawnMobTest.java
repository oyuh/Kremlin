package me.lawsonhart.kremlin.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.bukkit.Location;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.junit.jupiter.api.Test;

/**
 * /spawnmob finds a mob's options by reflection, so the check that matters is what that sweep
 * picks up and what it leaves behind: the mob's own variety, none of Entity's plumbing.
 */
class SpawnMobTest {

    @Test
    void findsTheOptionsThatMakeAMobItself() {
        assertTrue(SpawnMobCommand.discover(Horse.class).keySet().containsAll(Set.of("color", "style", "tamed", "owner")));
        assertTrue(SpawnMobCommand.discover(Wolf.class).keySet().containsAll(Set.of("variant", "collarcolor", "angry")));
        assertTrue(SpawnMobCommand.discover(Slime.class).containsKey("size"));
        assertTrue(SpawnMobCommand.discover(Villager.class).keySet().containsAll(Set.of("profession", "villagerlevel")));
    }

    @Test
    void leavesEntityPlumbingAndDeprecatedSettersAlone() {
        final Set<String> horse = SpawnMobCommand.discover(Horse.class).keySet();
        assertFalse(horse.contains("velocity"), "velocity is not a mob option");
        assertFalse(horse.contains("rotation"), "rotation is not a mob option");
        assertFalse(horse.contains("tickslived"), "ticks lived is not a mob option");
        assertFalse(horse.contains("customname"), "deprecated String setter must not shadow name:");
        assertFalse(horse.contains("op"), "a mob's op status is not a mob option");
        assertFalse(horse.contains("killer"), "bookkeeping setters are filtered as noise");
        assertTrue(horse.contains("glowing"), "the handful of Entity options kept must survive");
    }

    @Test
    void readsCoordinatesAbsoluteAndRelative() {
        final Location here = new Location(null, 10, 64, -5);
        assertEquals(100.0, SpawnMobCommand.coordinate("100", here, 0));
        assertEquals(64.0, SpawnMobCommand.coordinate("~", here, 1));
        assertEquals(-8.0, SpawnMobCommand.coordinate("~-3", here, 2));
        assertNull(SpawnMobCommand.coordinate("~", null, 0), "relative needs somewhere to be relative to");
        assertNull(SpawnMobCommand.coordinate("over_there", here, 0));
    }
}
