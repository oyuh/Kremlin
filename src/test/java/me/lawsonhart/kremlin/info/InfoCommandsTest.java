package me.lawsonhart.kremlin.info;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The info commands are all one executor reading {@code info.<label>} out of messages.yml, so
 * the two things that can go wrong are a label with no entry shipped for it, and a lookup that
 * does not read the shape the file is written in.
 */
class InfoCommandsTest {

    private static YamlConfiguration shipped() {
        try (var in = InfoCommandsTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(in, "messages.yml is not on the classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * A label with nothing shipped for it answers "not configured" on a fresh install, which
     * looks like a broken command rather than one waiting to be filled in.
     */
    @Test
    void everyLabelHasTextShippedForIt() {
        YamlConfiguration messages = shipped();
        for (final String label : InfoCommands.LABELS) {
            List<String> lines = messages.getStringList("info." + label);
            assertFalse(lines.isEmpty(), "info." + label + " ships with nothing to say");
        }
    }

    /** The lookup reads a list; a key written as one string still has to work. */
    @Test
    void aSingleLineEntryReadsAsWellAsAList() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                info:
                  discord: "one line"
                  rules:
                    - "first"
                    - "second"
                """));

        assertTrue(y.getStringList("info.discord").isEmpty(),
                "a plain string is not a list -- hence the fallback in Messages.lines");
        assertEquals("one line", y.getString("info.discord"));
        assertEquals(List.of("first", "second"), y.getStringList("info.rules"));
    }

    /** An unset key has to read as empty rather than as a one-element list of null. */
    @Test
    void anUnsetKeyIsEmptyBothWays() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("info: {}\n"));

        assertTrue(y.getStringList("info.vote").isEmpty());
        assertNull(y.getString("info.vote"));
    }

    /** Every label needs a plugin.yml entry, or the command simply does not exist. */
    @Test
    void everyLabelIsDeclaredAsACommand() {
        try (var in = InfoCommandsTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in);
            YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            var commands = plugin.getConfigurationSection("commands");
            assertNotNull(commands);
            for (final String label : InfoCommands.LABELS) {
                assertTrue(commands.contains(label), "/" + label + " is not declared in plugin.yml");
            }
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
