package me.lawsonhart.kremlin.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Format;
import me.lawsonhart.kremlin.core.VaultHook;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Chat formatting, replacing EssentialsChat.
 *
 * The format strings come straight out of an EssentialsChat config -- {@code chat.format} and
 * {@code chat.group-formats.<group>} -- and are read in that dialect on purpose, so nothing has
 * to be retyped. The group comes from Vault, which in practice means LuckPerms.
 *
 * Rendering happens through a Paper {@link io.papermc.paper.chat.ChatRenderer}, which is the same
 * hook EssentialsX uses when {@code paper-chat-events} is on. It matters that it is a renderer
 * rather than a rewrite of the message: other plugins still see the message the player actually
 * typed, and anything that decorates chat downstream keeps working.
 */
public final class ChatFormat implements Listener {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private final Combat plugin;
    private final VaultHook vault;
    private final Papi papi;
    private final Ignore ignore;

    private boolean enabled;
    private String format = "";
    private Map<String, String> groupFormats = Map.of();

    public ChatFormat(final Combat plugin, final VaultHook vault, final Papi papi, final Ignore ignore) {
        this.plugin = plugin;
        this.vault = vault;
        this.papi = papi;
        this.ignore = ignore;
    }

    public void load() {
        enabled = plugin.getConfig().getBoolean("chat.enabled", true);
        format = plugin.getConfig().getString("chat.format", "");
        final Map<String, String> groups = new HashMap<>();
        final var section = plugin.getConfig().getConfigurationSection("chat.group-formats");
        if (section != null) {
            // Group names are matched case-insensitively; a permissions plugin is not obliged to
            // agree with the config about capitalisation.
            for (final String group : section.getKeys(false)) {
                // isString first: getString on a nested section returns its toString(), not null.
                if (!section.isString(group)) continue;
                final String value = section.getString(group);
                if (value != null && !value.isEmpty()) groups.put(group.toLowerCase(Locale.ROOT), value);
            }
        }
        groupFormats = Map.copyOf(groups);
    }

    /**
     * HIGHEST, so we set the renderer last and win against any other chat formatter on the server
     * -- a renderer set at NORMAL is simply overwritten by one set later. ignoreCancelled keeps
     * the original reason for running late (slow chat, the rename prompt) satisfied.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        event.viewers().removeIf(viewer ->
                viewer instanceof Player p && ignore.ignores(p.getUniqueId(), event.getPlayer().getUniqueId()));
        if (!enabled) return;

        final String chosen = formatFor(event.getPlayer());
        if (chosen.isEmpty()) return; // no format configured: leave vanilla chat alone

        // Resolved once here rather than per viewer -- the format does not vary by who is reading.
        final Component line = render(event.getPlayer(), chosen, event.message());
        event.renderer((source, sourceDisplayName, message, viewer) -> line);
    }

    private String formatFor(final Player player) {
        if (groupFormats.isEmpty()) return format;
        final String group = vault.group(player);
        final String byGroup = group == null ? null : groupFormats.get(group.toLowerCase(Locale.ROOT));
        return byGroup != null ? byGroup : format;
    }

    Component render(final Player player, final String template, final Component message) {
        String text = Format.tokens(template, values(player));
        text = papi.apply(player, Format.papi(text));
        return Format.render(text, Placeholder.component("message", body(player, message)));
    }

    /** What the player typed, filtered down to the codes their permissions allow. */
    private Component body(final Player player, final Component message) {
        final Format.Allowed allowed = new Format.Allowed(
                Perms.may(player, "kremlin.chat.color", "essentials.chat.color"),
                Perms.may(player, "kremlin.chat.format", "essentials.chat.format"),
                Perms.may(player, "kremlin.chat.magic", "essentials.chat.magic"));
        return Format.body(PlainTextComponentSerializer.plainText().serialize(message), allowed);
    }

    /**
     * EssentialsChat's placeholders. Display name is handed over as legacy text rather than as a
     * component because it is being spliced into a format string -- the codes survive the round
     * trip and are parsed back out with everything else.
     */
    private Map<String, String> values(final Player player) {
        final Map<String, String> values = new HashMap<>();
        values.put("USERNAME", player.getName());
        values.put("DISPLAYNAME", LEGACY.serialize(player.displayName()));
        values.put("NICKNAME", Names.plainName(player));
        values.put("PREFIX", vault.prefix(player));
        values.put("SUFFIX", vault.suffix(player));
        values.put("GROUP", vault.group(player));
        final String world = player.getWorld().getName();
        values.put("WORLD", world);
        values.put("WORLDNAME", world);
        values.put("SHORTWORLDNAME", world.isEmpty() ? "" : world.substring(0, 1));

        final Team team = player.getScoreboard().getEntryTeam(player.getName());
        values.put("TEAMNAME", team == null ? "" : team.getName());
        values.put("TEAMPREFIX", team == null ? "" : LEGACY.serialize(team.prefix()));
        values.put("TEAMSUFFIX", team == null ? "" : LEGACY.serialize(team.suffix()));
        return values;
    }

    public String describe() {
        return !enabled ? "chat format off"
                : "chat format" + (groupFormats.isEmpty() ? "" : " +" + groupFormats.size() + " group(s)")
                + (vault.present() ? " via vault" : " (no vault)")
                + (papi.present() ? " +papi" : "");
    }
}
