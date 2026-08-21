package me.lawsonhart.kremlin.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The small per-player things: toggles, ignore lists, and whatever later features need to
 * remember about somebody between sessions.
 *
 * Deliberately untyped. Every feature that wants a value names its own key rather than growing a
 * field here, so adding /powertool or /tptoggle later is a change in that feature and not in this
 * class. Homes, inventories and punishments are big enough to earn files of their own.
 *
 * ponytail: one users.yml held in memory and rewritten whole. Fine for a server this size; split
 * per-uuid or move to SQLite if the file gets big enough for the rewrite to show up in a profile.
 */
public final class Users {

    private final Plugin plugin;
    private final File file;
    private YamlConfiguration data = new YamlConfiguration();

    public Users(final Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "users.yml");
    }

    public void load() {
        data = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    static String path(final UUID id, final String key) {
        return "players." + id + "." + key;
    }

    public boolean flag(final UUID id, final String key, final boolean fallback) {
        return data.getBoolean(path(id, key), fallback);
    }

    public String text(final UUID id, final String key) {
        return data.getString(path(id, key));
    }

    public List<String> list(final UUID id, final String key) {
        return data.getStringList(path(id, key));
    }

    /**
     * A whole sub-section as child key -> value, for the features that store several related
     * things under one key (powertool bindings, one per material). Empty when there is none.
     */
    public Map<String, String> map(final UUID id, final String key) {
        final ConfigurationSection section = data.getConfigurationSection(path(id, key));
        if (section == null) return Map.of();
        final Map<String, String> out = new LinkedHashMap<>();
        for (final String child : section.getKeys(false)) {
            // isString, not getString: a nested section stringifies rather than reading as null.
            if (section.isString(child)) out.put(child, section.getString(child));
        }
        return out;
    }

    /**
     * A stored location, or null. The world not being loaded reads as null rather than as a
     * location with no world, which would blow up the moment anyone teleported to it.
     */
    public Location location(final UUID id, final String key) {
        final Location where = data.getLocation(path(id, key));
        return where == null || where.getWorld() == null ? null : where;
    }

    /** Everyone we hold anything for. Used by /alts, which has to look across players. */
    public List<UUID> ids() {
        final ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return List.of();
        final List<UUID> out = new ArrayList<>();
        for (final String key : players.getKeys(false)) {
            try {
                out.add(UUID.fromString(key));
            } catch (final IllegalArgumentException ignored) {
                // Not a uuid, so not a player record. Skip it rather than fail the lookup.
            }
        }
        return out;
    }

    /** A null value clears the key rather than storing a null. */
    public void set(final UUID id, final String key, final Object value) {
        data.set(path(id, key), value);
        saveLater();
    }

    public void set(final UUID id, final String key, final Collection<String> values) {
        data.set(path(id, key), values.isEmpty() ? null : List.copyOf(values));
        saveLater();
    }

    /** Off the region thread, like homes.yml -- writing a file is never worth stalling a tick. */
    private void saveLater() {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> saveNow());
    }

    public synchronized void saveNow() {
        try {
            data.save(file);
        } catch (final IOException ex) {
            plugin.getLogger().severe("Could not save users.yml: " + ex);
        }
    }
}
