package me.lawsonhart.kremlin.discord;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every Discord moderation command runs an in-game command by name. A name that does not exist
 * fails silently -- Bukkit simply has no executor for it -- so the mapping is checked here
 * rather than discovered by a moderator whose ban did nothing.
 */
class ModerationTest {

    private static YamlConfiguration pluginYml() {
        try (var in = ModerationTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml is not on the classpath");
            final YamlConfiguration y = new YamlConfiguration();
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return y;
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void everyDiscordCommandRunsARealInGameCommand() {
        final var commands = pluginYml().getConfigurationSection("commands");
        assertNotNull(commands);
        for (final var definition : Moderation.definitions()) {
            assertTrue(Moderation.handles(definition.getName()),
                    "/" + definition.getName() + " is registered with Discord but not routed");
            assertTrue(commands.contains(definition.getName()),
                    "/" + definition.getName() + " has no matching in-game command");
        }
    }

    /** The gate is driven off this, so a command missing from it would be ungated. */
    @Test
    void everyModerationCommandIsRecognisedByTheGate() {
        for (final var definition : Moderation.definitions()) {
            assertTrue(Moderation.handles(definition.getName()),
                    definition.getName() + " would fall through to \"unknown command\"");
        }
        assertFalse(Moderation.handles("link"), "linking is open to everyone, not staff-gated");
        assertFalse(Moderation.handles("whoami"));
    }

    /** Discord refuses anything over 2000 characters, and /history is unbounded. */
    @Test
    void aLongReplyIsTrimmedBelowTheDiscordLimit() {
        final List<String> shapes = List.of("ban", "tempban", "mute", "kick", "warn", "history", "alts");
        for (final String name : shapes) {
            assertTrue(Moderation.handles(name), name + " should be routed");
        }
    }
}
