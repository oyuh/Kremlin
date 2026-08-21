package me.lawsonhart.kremlin.chat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import me.lawsonhart.kremlin.core.EssentialsImport;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries the chat settings over from an EssentialsX config, once.
 *
 * Only keys the admin actually set are copied, and only into keys we have not already got a value
 * for, so a second start is a no-op and hand-edits are never clobbered. Everything is read out of
 * {@code plugins/Essentials/config.yml} in place -- the file is not touched.
 */
public final class ChatImport {

    /** Essentials key -> our key. The chat format keys keep their shape, so this is mostly 1:1. */
    private static final String[][] KEYS = {
            {"chat.format", "chat.format"},
            {"chat.radius", "chat.radius"},
            {"custom-join-message", "custom-join-message"},
            {"custom-quit-message", "custom-quit-message"},
            {"newbies.announce-format", "newbies.announce-format"},
    };

    private ChatImport() {
    }

    /**
     * Returns what it copied, for the log. Nothing happens when Essentials was never installed
     * or when we have already been through this once.
     */
    public static List<String> run(final Plugin plugin) {
        final FileConfiguration config = plugin.getConfig();
        if (config.getBoolean("chat.imported", false)) return List.of();

        final YamlConfiguration essentials = EssentialsImport.config(plugin);
        if (essentials == null) return List.of();

        // The scalar keys come across the shared way; the group formats below need a shape check
        // of their own, which is the whole reason the flag is raised here rather than in there.
        final List<String> copied = new ArrayList<>(EssentialsImport.copyKeys(plugin, essentials, KEYS));

        final ConfigurationSection groups = essentials.getConfigurationSection("chat.group-formats");
        if (groups != null && !config.isSet("chat.group-formats")) {
            int taken = 0;
            for (final String group : groups.getKeys(false)) {
                // isString, not getString: a group whose value is a section is a per-chat-type
                // format (question/shout), and getString would hand back its toString() rather
                // than null -- importing "MemorySection[path=...]" as somebody's chat format.
                if (!groups.isString(group)) {
                    plugin.getLogger().warning("Skipping chat.group-formats." + group
                            + " -- per-chat-type formats (question/shout) are not supported.");
                    continue;
                }
                config.set("chat.group-formats." + group, groups.getString(group));
                taken++;
            }
            if (taken > 0) copied.add("chat.group-formats");
        }

        config.set("chat.imported", true);
        plugin.saveConfig();
        return copied;
    }
}
