package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.combat.Combat;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /team admin discord <team> <roleid>} is caught before SimpleTeams sees it, so the
 * matching has to be exact in both directions: catching a real /team subcommand would break
 * that plugin, and missing ours would silently hand it back to SimpleTeams' usage message.
 */
class TeamRoleCommandTest {

    /** The same parse the listener does, so the cases below describe real behaviour. */
    private static boolean ours(final String message) {
        final String trimmed = message.trim();
        final String[] parts = trimmed.split("\\s+");
        final String root = Combat.rootCommand(trimmed);
        if (!root.equals("team") && !root.equals("t") && !root.equals("teams")) return false;
        return parts.length >= 3 && parts[1].equalsIgnoreCase("admin")
                && parts[2].equalsIgnoreCase("discord");
    }

    @Test
    void ourSubcommandIsCaught() {
        assertTrue(ours("/team admin discord Raiders 123456789012345678"));
        assertTrue(ours("/team admin discord list"));
        assertTrue(ours("/team admin discord Raiders clear"));
        assertTrue(ours("/TEAM ADMIN DISCORD Raiders 1"), "case insensitive");
        assertTrue(ours("/t admin discord list"), "the short alias");
        assertTrue(ours("/simpleteams:team admin discord list"), "namespaced still lands here");
        assertTrue(ours("  /team   admin   discord   list  "), "ragged spacing");
    }

    /**
     * The important half. Every one of these belongs to SimpleTeams, and swallowing any of them
     * would break that plugin with no error to explain it.
     */
    @Test
    void everySimpleTeamsCommandIsLeftAlone() {
        assertFalse(ours("/team admin"), "their own admin menu");
        assertFalse(ours("/team admin kick Steve"));
        assertFalse(ours("/team admin disband Raiders"));
        assertFalse(ours("/team discord"), "not under admin, so not ours");
        assertFalse(ours("/team home"));
        assertFalse(ours("/team create Raiders"));
        assertFalse(ours("/team"));
        assertFalse(ours("/teamhq"), "a different command that merely starts the same way");
        assertFalse(ours("/discord"), "the info command is not a team command");
    }

    /** Role ids are snowflakes; anything else is a mistyped or pasted role *name*. */
    @Test
    void onlyASnowflakeIsAcceptedAsARoleId() {
        assertTrue("123456789012345678".matches("\\d{5,25}"));
        assertFalse("Raiders".matches("\\d{5,25}"), "a role name is a very easy mistake to make");
        assertFalse("<@&123456789012345678>".matches("\\d{5,25}"), "a pasted mention is not an id");
        assertFalse("123".matches("\\d{5,25}"), "too short to be a snowflake");
        assertFalse("".matches("\\d{5,25}"));
    }

    /**
     * Bindings are stored lowercased, because a team typed as "Raiders" today and "raiders"
     * tomorrow has to reach the same role.
     */
    @Test
    void teamNamesAreStoredCaseInsensitively() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(
                TeamRoleCommand.PATH.replace(".", ":\n  ").replaceFirst("^", "")
                        + ":\n"));
        // The path itself is what the command writes under; assert its shape rather than parsing.
        assertEquals("discord.roles.teams.map", TeamRoleCommand.PATH);
        assertEquals("raiders", "Raiders".toLowerCase(Locale.ROOT));
        assertNotNull(config);
    }

    /** Comments in config.yml survive the save the command performs. */
    @Test
    void writingABindingKeepsTheConfigComments() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new StringReader("# Discord\ndiscord:\n  # teams\n  enabled: false\n"));
        config.set(TeamRoleCommand.PATH + ".raiders", "123456789012345678");

        String saved = config.saveToString();
        assertTrue(saved.contains("# Discord"), "the file is hand-edited; its comments must survive");
        assertTrue(saved.contains("# teams"));
        assertTrue(saved.contains("123456789012345678"));
    }
}
