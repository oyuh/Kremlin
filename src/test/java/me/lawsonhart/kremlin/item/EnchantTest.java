package me.lawsonhart.kremlin.item;

import org.bukkit.enchantments.Enchantment;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The alias table and the unsafe-level gate.
 *
 * Registry lookups need a running server, so these pin the two halves that do not: that every
 * alias points at a name the registry could actually answer to, and that the level clamp can
 * never hand the API something it will refuse.
 */
class EnchantTest {

    /**
     * An alias pointing at another alias would resolve to nothing, because the table is applied
     * once and the result goes straight to the registry.
     */
    @Test
    void noAliasPointsAtAnotherAlias() {
        for (final Map.Entry<String, String> e : Enchant.ALIASES.entrySet()) {
            if (e.getKey().equals(e.getValue())) continue; // an identity entry is harmless
            assertFalse(Enchant.ALIASES.containsKey(e.getValue()),
                    e.getKey() + " -> " + e.getValue() + " -> " + Enchant.ALIASES.get(e.getValue())
                            + ": aliases are resolved once, so this would never reach the registry");
        }
    }

    /** The table is looked up on a lowercased key, so an upper-case entry could never match. */
    @Test
    void everyAliasIsStoredInTheFormItIsLookedUpBy() {
        for (final String key : Enchant.ALIASES.keySet()) {
            assertEquals(key.toLowerCase(Locale.ROOT), key, key + " would never be found");
            assertFalse(key.contains(" "), key + " has a space; spaces become underscores first");
            assertFalse(key.startsWith("minecraft:"), key + " keeps a namespace that is stripped first");
        }
    }

    /** The old Bukkit constants are the ones a long-standing habit or old config produces. */
    @Test
    void theOldBukkitNamesAreCovered() {
        assertEquals("sharpness", Enchant.ALIASES.get("damage_all"));
        assertEquals("protection", Enchant.ALIASES.get("protection_environmental"));
        assertEquals("fortune", Enchant.ALIASES.get("loot_bonus_blocks"));
        assertEquals("efficiency", Enchant.ALIASES.get("dig_speed"));
        assertEquals("unbreaking", Enchant.ALIASES.get("durability"));
        assertEquals("power", Enchant.ALIASES.get("arrow_damage"));
        assertEquals("infinity", Enchant.ALIASES.get("arrow_infinite"));
    }

    /**
     * The modern registry key is already the friendly name, so anything the registry answers to
     * must NOT be in the table -- an entry there would be dead weight at best and a wrong
     * redirect at worst.
     */
    @Test
    void theTableOnlyCoversWhatTheRegistryWouldMiss() {
        for (final Map.Entry<String, String> e : Enchant.ALIASES.entrySet()) {
            assertNotEquals(e.getKey(), e.getValue(),
                    e.getKey() + " maps to itself; the registry already answers to it");
        }
        assertFalse(Enchant.ALIASES.containsKey("looting"), "the registry answers to this already");
        assertFalse(Enchant.ALIASES.containsKey("silk_touch"));
        assertFalse(Enchant.ALIASES.containsKey("protection"));
    }

    /**
     * Every alias has to point at an enchantment that exists.
     *
     * Caught a real one: {@code sweeping_edge -> sweeping} was written backwards, and since
     * "sweeping" is not a key on any current version it broke the exact enchantment the alias was
     * added to help. Reading the constant NAMES off the class avoids initialising them, which
     * would need a running server.
     */
    @Test
    void everyAliasPointsAtAnEnchantmentThatExists() {
        final Set<String> real = new HashSet<>();
        for (final Field f : Enchantment.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Enchantment.class) {
                real.add(f.getName().toLowerCase(Locale.ROOT));
            }
        }
        assertTrue(real.size() > 30, "expected the enchantment constants, found " + real.size());
        assertTrue(real.contains("sweeping_edge"), "sanity: the real key for the sweeping alias");

        for (final Map.Entry<String, String> e : Enchant.ALIASES.entrySet()) {
            assertTrue(real.contains(e.getValue()),
                    e.getKey() + " -> " + e.getValue() + ", which is not an enchantment");
        }
    }

    @Test
    void aBlankOrMissingNameResolvesToNothing() {
        assertNull(Enchant.resolve(null));
        assertNull(Enchant.resolve(""));
        assertNull(Enchant.resolve("   "));
    }
}
