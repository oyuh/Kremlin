package me.lawsonhart.kremlin.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Every message the plugin sends, out of messages.yml.
 *
 * They used to be a {@code messages:} block in config.yml. That file is settings now, and an
 * existing install is carried across on first start by {@link #migrateFromConfig} rather than
 * being asked to retype anything.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String FILE = "messages.yml";
    /** The block this used to live in, still read once so an old config.yml comes across. */
    private static final String OLD_SECTION = "messages";

    private final Plugin plugin;
    private final File file;
    private YamlConfiguration messages = new YamlConfiguration();

    public Messages(final Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    /** Call after the config is available; safe to call again on reload. */
    public void load() {
        if (!file.isFile()) {
            if (!migrateFromConfig()) plugin.saveResource(FILE, false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        applyBundledDefaults();
    }

    /**
     * Lifts a pre-split config.yml's {@code messages:} block into messages.yml, flattened to
     * top-level keys. Returns false when there is nothing to carry -- a fresh install.
     */
    private boolean migrateFromConfig() {
        final FileConfiguration config = plugin.getConfig();
        final ConfigurationSection old = config.getConfigurationSection(OLD_SECTION);
        if (old == null || old.getKeys(false).isEmpty()) return false;

        final YamlConfiguration out = flatten(old);
        try {
            out.save(file);
        } catch (final IOException ex) {
            plugin.getLogger().severe("Could not write " + FILE + ": " + ex);
            return false;
        }
        plugin.getLogger().info("Moved " + old.getKeys(false).size()
                + " messages out of config.yml into " + FILE + ".");
        return true;
    }

    /**
     * The {@code messages:} block as a file of its own: {@code messages.home-set} becomes
     * {@code home-set}. Only the user's own wording moves -- anything they never touched is
     * covered by the jar's copy underneath.
     */
    static YamlConfiguration flatten(final ConfigurationSection old) {
        final YamlConfiguration out = new YamlConfiguration();
        for (final String key : old.getKeys(false)) out.set(key, old.get(key));
        return out;
    }

    /**
     * Layer the jar's copy underneath the user's file, so a key added in a later version still
     * reads back on an install whose messages.yml predates it. Same reason config.yml does it.
     */
    private void applyBundledDefaults() {
        try (InputStream in = plugin.getResource(FILE)) {
            if (in == null) return;
            messages.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (final IOException ex) {
            plugin.getLogger().warning("Couldn't read bundled " + FILE + ": " + ex);
        }
    }

    /**
     * A key holding several lines, for the commands that print a block of text. Empty when the
     * key is unset -- callers say so rather than printing nothing.
     */
    public List<Component> lines(final String key) {
        final List<String> raw = messages.getStringList(key);
        if (raw.isEmpty()) {
            final String single = messages.getString(key);
            if (single == null || single.isEmpty()) return List.of();
            return List.of(MM.deserialize(single));
        }
        return raw.stream().map(MM::deserialize).toList();
    }

    public Component get(final String key, final TagResolver... resolvers) {
        // No explicit default here on purpose: getString(path, def) returns def outright and
        // never falls through to the defaults layer set up in applyBundledDefaults().
        final String raw = messages.getString(key);
        if (raw == null || raw.isEmpty()) {
            // Should be unreachable now the jar's copy is layered in as defaults, but an
            // empty message here once meant "silently cancel the command with no reason given".
            plugin.getLogger().warning("Message '" + key + "' is empty -- check plugins/"
                    + plugin.getName() + "/" + FILE);
            return Component.text("[Kremlin] blocked (missing message: " + key + ")");
        }
        return MM.deserialize(raw, resolvers);
    }
}
