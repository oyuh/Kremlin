package me.lawsonhart.kremlin.chat;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Durations;
import me.lawsonhart.kremlin.punish.Punishments;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * {@code %kremlin_...%} for PlaceholderAPI, so tab lists, nametag plugins and scoreboards can
 * show what this plugin knows.
 *
 * <h2>Two forms of every name</h2>
 * Most consumers of a placeholder paste the result into a legacy-coded string, which means a
 * component has to be serialised on the way out. Each name therefore comes in two: the plain
 * one, and a {@code _formatted} twin carrying the colours as {@code &} codes. A tab plugin that
 * does not understand colour gets something readable either way, and one that does gets the
 * gradient.
 *
 * <h2>Registration</h2>
 * This class extends a PlaceholderAPI type, so it is only ever loaded when PlaceholderAPI is
 * actually installed -- the JVM does not resolve it until {@link #install} is called, and that
 * is guarded. There is no hard dependency.
 */
public final class Placeholders extends PlaceholderExpansion {

    /** The form tab and nametag plugins understand: legacy codes, hex included. */
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private final Combat plugin;

    private Placeholders(final Combat plugin) {
        this.plugin = plugin;
    }

    /** Registers the expansion when PlaceholderAPI is present. Safe to call regardless. */
    public static void install(final Combat plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.getLogger().info("PlaceholderAPI not installed -- %kremlin_...% placeholders are off.");
            return;
        }
        try {
            if (new Placeholders(plugin).register()) {
                plugin.getLogger().info("Registered the %kremlin_...% placeholders.");
            }
        } catch (final Throwable t) {
            plugin.getLogger().warning("Could not register placeholders (" + t + ").");
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "kremlin";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Lawson Hart";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Kremlin outlives any single PlaceholderAPI reload, so the expansion must not be unloaded. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(final OfflinePlayer who, @NotNull final String params) {
        if (who == null) return "";
        final Player online = who.getPlayer();

        return switch (params.toLowerCase(Locale.ROOT)) {
            // --- names -------------------------------------------------------
            case "name" -> who.getName() == null ? "" : who.getName();
            case "nick" -> plain(plugin.displayName(who));
            case "nick_formatted" -> legacy(plugin.displayName(who));
            case "has_nick" -> String.valueOf(plugin.getNicknames().rawOf(who.getUniqueId()) != null);

            // --- team --------------------------------------------------------
            case "team" -> orEmpty(plugin.getTeamHook().teamNameOf(who.getUniqueId()));
            case "has_team" -> String.valueOf(plugin.getTeamHook().teamNameOf(who.getUniqueId()) != null);

            // --- discord -----------------------------------------------------
            case "discord" -> discordName(who);
            case "discord_id" -> id(plugin.getLinks().discordOf(who.getUniqueId()));
            case "linked" -> String.valueOf(plugin.getLinks().isLinked(who.getUniqueId()));

            // --- combat ------------------------------------------------------
            case "combat" -> online == null ? "false" : String.valueOf(plugin.denyCombat(online) != null);
            case "combat_time" -> online == null ? "0" : Combat.secs(plugin.combatLeft(online));

            // --- punishment --------------------------------------------------
            case "muted" -> String.valueOf(mute(who) != null);
            case "mute_time" -> {
                final Punishments.Entry mute = mute(who);
                yield mute == null ? "" : Durations.describe(mute.remaining());
            }
            case "mute_reason" -> {
                final Punishments.Entry mute = mute(who);
                yield mute == null ? "" : mute.reason();
            }

            // --- homes -------------------------------------------------------
            case "homes" -> String.valueOf(plugin.getHomes().homeNamesOf(who.getUniqueId()).size());

            default -> null; // null, not "": PlaceholderAPI leaves an unknown placeholder as typed
        };
    }

    // ---------------------------------------------------------------- helpers

    private Punishments.Entry mute(final OfflinePlayer who) {
        return plugin.getPunishments() == null ? null : plugin.getPunishments().muteOf(who.getUniqueId());
    }

    private String discordName(final OfflinePlayer who) {
        final long discord = plugin.getLinks().discordOf(who.getUniqueId());
        if (discord == 0L) return "";
        // Cache only: a placeholder can be asked for once per player per tick by a tab plugin,
        // and a blocking Discord lookup at that rate would be a disaster.
        final var jda = plugin.getBot().ready() ? plugin.getBot().jda() : null;
        final var user = jda == null ? null : jda.getUserById(discord);
        return user == null ? String.valueOf(discord) : user.getName();
    }

    private static String id(final long value) {
        return value == 0L ? "" : String.valueOf(value);
    }

    private static String orEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String legacy(final Component component) {
        return LEGACY.serialize(component);
    }
}
