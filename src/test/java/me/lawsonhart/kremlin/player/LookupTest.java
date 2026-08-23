package me.lawsonhart.kremlin.player;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The /whois detail block is a pile of message keys, and a missing one is not a compile error --
 * it is a line reading "[Kremlin] blocked (missing message: ...)" in front of whoever ran it.
 *
 * The button tooltips are the real trap: {@code Lookup.button} builds them by sticking
 * {@code -hover} on the end of the button's own key, so adding a button without its tooltip
 * compiles perfectly and breaks only when somebody hovers it.
 */
class LookupTest {

    private static YamlConfiguration messages() {
        try (InputStream in = LookupTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(in, "messages.yml is not on the classpath");
            final YamlConfiguration y = new YamlConfiguration();
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return y;
        } catch (final Exception ex) {
            throw new AssertionError("messages.yml does not parse: " + ex.getMessage(), ex);
        }
    }

    /** Every key {@code Lookup} asks for by name. */
    @Test
    void everyLineOfTheDetailBlockHasItsMessage() {
        final YamlConfiguration messages = messages();
        for (final String key : List.of(
                "realname", "realname-usage",
                "whois-line-uuid", "whois-hover-uuid",
                "whois-line-team", "whois-line-team-none",
                "whois-line-homes", "whois-hover-homes", "whois-line-homes-none",
                "whois-line-punishments", "whois-hover-punishments", "whois-line-punishments-none",
                "whois-line-muted", "whois-line-banned",
                "whois-line-alts", "whois-hover-alts",
                "whois-line-discord", "whois-line-playtime",
                "whois-buttons",
                "ping-usage", "ping-self", "ping-other", "ping-offline",
                // The punishment tooltip reuses /history's own line rather than inventing one.
                "history-line")) {
            final String value = messages.getString(key);
            assertNotNull(value, key + " is missing from messages.yml");
            assertFalse(value.isBlank(), key + " is empty, which reads as a blocked message");
        }
    }

    /** The derived tooltip key, which nothing else would catch. */
    @Test
    void everyButtonHasATooltip() {
        final YamlConfiguration messages = messages();
        final List<String> buttons = new ArrayList<>();
        for (final String key : messages.getKeys(false)) {
            if (key.startsWith("whois-button-") && !key.endsWith("-hover")) buttons.add(key);
        }
        assertFalse(buttons.isEmpty(), "no buttons found -- this test would pass vacuously");
        for (final String button : buttons) {
            assertNotNull(messages.getString(button + "-hover"),
                    button + " has no " + button + "-hover, so hovering it shows an error");
        }
    }

    /**
     * The ping bands. A player who has not been measured yet reports -1, which must not read as
     * the fastest connection on the server -- which is exactly what a plain {@code < 150} does.
     */
    @Test
    void pingIsColouredByHowBadItIs() {
        assertEquals("<gray>", Lookup.colour(-1), "unmeasured is not excellent");
        assertEquals("<green>", Lookup.colour(0));
        assertEquals("<green>", Lookup.colour(149));
        assertEquals("<yellow>", Lookup.colour(150));
        assertEquals("<yellow>", Lookup.colour(299));
        assertEquals("<red>", Lookup.colour(300));
        assertEquals("<red>", Lookup.colour(5000));
    }

    /** Both new commands have to be dispatched by the server, and gated by a node someone can hold. */
    @Test
    void theNewCommandsAreRegistered() {
        final YamlConfiguration plugin;
        try (InputStream in = LookupTest.class.getResourceAsStream("/plugin.yml")) {
            plugin = new YamlConfiguration();
            plugin.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final Exception ex) {
            throw new AssertionError("plugin.yml does not parse", ex);
        }
        assertNotNull(plugin.getConfigurationSection("commands.realname"));
        assertTrue(plugin.getStringList("commands.realname.aliases").contains("erealname"),
                "Essentials' label has to keep working");
        // Permission names contain dots, which YamlConfiguration reads back as nesting -- the
        // server parses plugin.yml's raw map instead, so this is only how the test has to look.
        assertNotNull(plugin.getString("permissions.kremlin.realname.default"),
                "kremlin.realname needs a default, or nobody has it");
        assertNotNull(plugin.getString("permissions.kremlin.playerlist.whois.default"),
                "kremlin.playerlist.whois needs a default, or nobody has it");
        assertNotNull(plugin.getConfigurationSection("commands.ping"));
        assertNotNull(plugin.getString("permissions.kremlin.ping.default"));
        assertNotNull(plugin.getString("permissions.kremlin.ping.others.default"));
    }
}
