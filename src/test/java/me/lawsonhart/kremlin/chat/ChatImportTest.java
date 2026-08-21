package me.lawsonhart.kremlin.chat;

import me.lawsonhart.kremlin.core.Format;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape of an EssentialsX chat config, as it actually appears on the server this replaces.
 * The importer reads these keys, so if their nesting is ever misread the settings silently do
 * not come across and chat quietly falls back to vanilla.
 */
class ChatImportTest {

    /** Trimmed from the real plugins/Essentials/config.yml. */
    private static final String ESSENTIALS = """
            chat:
              radius: 0
              format: '{simpleteams_prefix_formatted} {PREFIX}{DISPLAYNAME}&r: {MESSAGE}'
              group-formats:
                default: '{simpleteams_prefix_formatted} {PREFIX}{DISPLAYNAME}&r: {MESSAGE}'
                admin: '&6* &r{simpleteams_prefix_formatted} {PREFIX}{DISPLAYNAME}&r: {MESSAGE}'
            custom-join-message: "&a+ &r{PLAYER}"
            custom-quit-message: "&c- &r{PLAYER}"
            newbies:
              announce-format: '&dWelcome {DISPLAYNAME}&d to the server!'
            """;

    private static YamlConfiguration essentials() {
        return YamlConfiguration.loadConfiguration(new StringReader(ESSENTIALS));
    }

    @Test
    void everyKeyTheImporterCopiesIsWhereItExpects() {
        YamlConfiguration e = essentials();

        assertEquals("{simpleteams_prefix_formatted} {PREFIX}{DISPLAYNAME}&r: {MESSAGE}",
                e.getString("chat.format"));
        assertEquals("&a+ &r{PLAYER}", e.getString("custom-join-message"));
        assertEquals("&c- &r{PLAYER}", e.getString("custom-quit-message"));
        assertEquals("&dWelcome {DISPLAYNAME}&d to the server!", e.getString("newbies.announce-format"));
        assertNotNull(e.getConfigurationSection("chat.group-formats"));
        assertEquals(2, e.getConfigurationSection("chat.group-formats").getKeys(false).size());
    }

    /**
     * A group whose value is a section rather than a string is a per-chat-type format
     * (question/shout). Those are skipped, so reading them as a string has to give null rather
     * than something that looks importable.
     */
    @Test
    void aPerChatTypeGroupFormatIsRecognisedAsUnsupported() {
        YamlConfiguration e = YamlConfiguration.loadConfiguration(new StringReader("""
                chat:
                  group-formats:
                    plain: '{DISPLAYNAME}: {MESSAGE}'
                    admins:
                      question: '{DISPLAYNAME} asks: {MESSAGE}'
                """));
        var groups = e.getConfigurationSection("chat.group-formats");

        assertTrue(groups.isString("plain"));
        assertEquals("{DISPLAYNAME}: {MESSAGE}", groups.getString("plain"));

        assertFalse(groups.isString("admins"), "isString is the guard the importer relies on");
        assertNotNull(groups.getString("admins"),
                "getString stringifies the section rather than returning null -- which is exactly"
                + " why a plain null check would import MemorySection[...] as somebody's format");
    }

    /** The imported format has to survive the round trip through our own resolver. */
    @Test
    void whatIsImportedIsSomethingWeCanRender() {
        String imported = essentials().getString("chat.group-formats.admin");
        String resolved = Format.tokens(imported,
                java.util.Map.of("PREFIX", "&7[Admin] ", "DISPLAYNAME", "&bLawson"));

        assertTrue(resolved.startsWith("&6* &r"), "the group marker survives");
        assertTrue(resolved.endsWith(Format.MESSAGE_TAG));
        assertTrue(resolved.contains("{simpleteams_prefix_formatted}"), "left for PlaceholderAPI");
    }
}
