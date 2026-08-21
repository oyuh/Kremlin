package me.lawsonhart.kremlin.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped YAML has to parse.
 *
 * This exists because it did not, once: a description containing ": " read as a nested mapping
 * and made plugin.yml invalid, which stops the plugin loading outright with nothing but a server
 * log line to go on. {@code YamlConfiguration.loadConfiguration} hides that -- it catches the
 * parse error and returns an empty config -- so this uses {@code load}, which throws.
 */
class ResourcesTest {

    private static YamlConfiguration parse(final String name) {
        try (InputStream in = ResourcesTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name + " is not on the classpath");
            final YamlConfiguration y = new YamlConfiguration();
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return y;
        } catch (final Exception ex) {
            throw new AssertionError(name + " does not parse: " + ex.getMessage(), ex);
        }
    }

    @Test
    void everyShippedFileParses() {
        assertFalse(parse("plugin.yml").getKeys(false).isEmpty());
        assertFalse(parse("config.yml").getKeys(false).isEmpty());
        assertFalse(parse("messages.yml").getKeys(false).isEmpty());
    }

    /** The bits of plugin.yml the server refuses to start without. */
    @Test
    void pluginYmlHasWhatTheServerNeeds() {
        final YamlConfiguration plugin = parse("plugin.yml");

        assertEquals("Kremlin", plugin.getString("name"));
        assertEquals("me.lawsonhart.kremlin.Kremlin", plugin.getString("main"));
        assertNotNull(plugin.getString("version"));
        assertNotNull(plugin.getString("api-version"));
        assertTrue(plugin.getBoolean("folia-supported"), "this plugin schedules per-region throughout");
        assertNotNull(plugin.getConfigurationSection("commands"));
        assertNotNull(plugin.getConfigurationSection("permissions"));
    }

    /** EssentialsX is gone; depending on it again would be a step backwards. */
    @Test
    void nothingDependsOnEssentialsAnyMore() {
        final YamlConfiguration plugin = parse("plugin.yml");
        for (final String key : new String[]{"depend", "softdepend", "loadbefore"}) {
            for (final String named : plugin.getStringList(key)) {
                assertFalse(named.toLowerCase(java.util.Locale.ROOT).startsWith("essentials"),
                        key + " still names " + named);
            }
        }
    }

    /**
     * Every command needs a description and a usage line, because both are what a player sees
     * when they get the syntax wrong.
     */
    @Test
    void everyCommandIsDescribed() {
        final var commands = parse("plugin.yml").getConfigurationSection("commands");
        assertNotNull(commands);
        for (final String name : commands.getKeys(false)) {
            assertNotNull(commands.getString(name + ".description"), "/" + name + " has no description");
            assertNotNull(commands.getString(name + ".usage"), "/" + name + " has no usage");
        }
    }

    /** An alias claimed by two commands means one of them silently loses it. */
    @Test
    void noTwoCommandsClaimTheSameLabel() {
        final var commands = parse("plugin.yml").getConfigurationSection("commands");
        assertNotNull(commands);
        final java.util.Map<String, String> owner = new java.util.HashMap<>();
        for (final String name : commands.getKeys(false)) {
            final java.util.List<String> labels = new java.util.ArrayList<>();
            labels.add(name);
            labels.addAll(commands.getStringList(name + ".aliases"));
            for (final String label : labels) {
                final String taken = owner.put(label.toLowerCase(java.util.Locale.ROOT), name);
                assertNull(taken, "/" + label + " is claimed by both " + taken + " and " + name);
            }
        }
    }
}
