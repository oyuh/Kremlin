package me.lawsonhart.kremlin.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies settings out of an EssentialsX config, once per group of keys.
 *
 * Each caller brings its own flag key, so chat settings and nickname settings import
 * independently -- a server that already started once after the chat work landed still picks the
 * nickname settings up. Nothing is ever written over a value we already hold, so hand edits win
 * and a second start is a no-op.
 */
public final class EssentialsImport {

    private EssentialsImport() {
    }

    /** The Essentials config, or null when it was never installed. */
    public static YamlConfiguration config(final Plugin plugin) {
        final File source = new File(plugin.getDataFolder().getParentFile(), "Essentials/config.yml");
        return source.isFile() ? YamlConfiguration.loadConfiguration(source) : null;
    }

    /** {@code plugins/Essentials/userdata}, or null when there is none to read. */
    public static File userdata(final Plugin plugin) {
        final File dir = new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata");
        return dir.isDirectory() ? dir : null;
    }

    /**
     * Copies each {@code {theirKey, ourKey}} pair that they set and we have not, then raises
     * {@code flag}. Returns the keys it wrote, for the log.
     */
    public static List<String> copy(final Plugin plugin, final String flag, final String[][] keys) {
        if (plugin.getConfig().getBoolean(flag, false)) return List.of();
        final YamlConfiguration essentials = config(plugin);
        if (essentials == null) return List.of();

        final List<String> copied = copyKeys(plugin, essentials, keys);
        plugin.getConfig().set(flag, true);
        plugin.saveConfig();
        return copied;
    }

    /**
     * The copy itself, without touching a flag or saving -- for callers that have extra work to
     * do under the same flag, and must not raise it until that work is done too.
     */
    public static List<String> copyKeys(final Plugin plugin, final YamlConfiguration essentials,
                                        final String[][] keys) {
        final FileConfiguration config = plugin.getConfig();
        final List<String> copied = new ArrayList<>();
        for (final String[] pair : keys) {
            if (!essentials.isSet(pair[0]) || config.isSet(pair[1])) continue;
            config.set(pair[1], essentials.get(pair[0]));
            copied.add(pair[1]);
        }
        return copied;
    }
}
