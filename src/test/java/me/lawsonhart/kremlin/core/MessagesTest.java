package me.lawsonhart.kremlin.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Messages moved out of config.yml into messages.yml. An existing server has its own wording in
 * the old place, so the one-time lift has to bring it across intact -- getting this wrong resets
 * every message on the server to the shipped defaults, silently.
 */
class MessagesTest {

    private static YamlConfiguration config(final String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }

    @Test
    void theOldMessagesBlockIsLiftedToTopLevelKeys() {
        YamlConfiguration old = config("""
                combat-seconds: 15
                messages:
                  home-set: "mine"
                  tpa-self: "no"
                """);

        YamlConfiguration moved = Messages.flatten(old.getConfigurationSection("messages"));

        assertEquals("mine", moved.getString("home-set"), "read back without the messages. prefix");
        assertEquals("no", moved.getString("tpa-self"));
        assertNull(moved.getString("messages.home-set"), "the old nesting must not survive");
        assertNull(moved.getString("combat-seconds"), "settings stay in config.yml");
    }

    /** The lift only carries what the admin actually wrote; the jar covers the rest. */
    @Test
    void whatTheAdminNeverTouchedFallsThroughToTheShippedCopy() {
        YamlConfiguration moved = Messages.flatten(
                config("messages:\n  home-set: \"mine\"\n").getConfigurationSection("messages"));
        YamlConfiguration shipped = config("home-set: \"shipped\"\ntpa-self: \"shipped too\"\n");

        assertNull(moved.getString("tpa-self"), "not carried, because it was never overridden");

        moved.setDefaults(shipped);
        assertEquals("mine", moved.getString("home-set"), "the admin's wording still wins");
        assertEquals("shipped too", moved.getString("tpa-self"), "and the gap is filled");
    }

    @Test
    void aFreshInstallHasNothingToCarry() {
        assertNull(config("combat-seconds: 15\n").getConfigurationSection("messages"),
                "no messages block means saveResource ships the file instead");
    }
}
