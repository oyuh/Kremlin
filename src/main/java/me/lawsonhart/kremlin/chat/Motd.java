package me.lawsonhart.kremlin.chat;

import me.lawsonhart.kremlin.combat.Combat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.List;
import java.util.Map;

/**
 * The MOTD in the multiplayer server list, out of config.yml.
 *
 * Written in MiniMessage, same dialect as everything else the plugin sends -- Paper hands the
 * ping event a Component, so gradients and hex survive the trip to the client untouched.
 *
 * A line starting with {@code <center>} is padded with spaces until it sits in the middle of the
 * list entry. That has to be measured in pixels rather than characters because the game's font is
 * proportional: an "i" is a third the width of an "m".
 */
public final class Motd implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** The opt-in marker, stripped before the line is parsed. */
    private static final String CENTER = "<center>";
    /** A space is 3px of glyph plus 1px of spacing. */
    private static final int SPACE_PX = 4;
    /** Most glyphs are 5px plus 1px of spacing; only the odd ones out are listed below. */
    private static final int DEFAULT_PX = 6;

    /** Rendered width in pixels, spacing included. Minecraft's default font. */
    private static final Map<Character, Integer> WIDTHS = Map.ofEntries(
            Map.entry(' ', 4), Map.entry('!', 2), Map.entry('"', 4), Map.entry('\'', 2),
            Map.entry('(', 5), Map.entry(')', 5), Map.entry('*', 4), Map.entry(',', 2),
            Map.entry('.', 2), Map.entry(':', 2), Map.entry(';', 2), Map.entry('<', 5),
            Map.entry('>', 5), Map.entry('@', 7), Map.entry('[', 4), Map.entry(']', 4),
            Map.entry('`', 3), Map.entry('f', 5), Map.entry('i', 2), Map.entry('k', 5),
            Map.entry('l', 3), Map.entry('t', 5), Map.entry('{', 5), Map.entry('}', 5),
            Map.entry('|', 2), Map.entry('~', 7), Map.entry('I', 4));

    private final Combat plugin;

    private boolean enabled;
    private List<String> lines = List.of();
    private int centerWidth;

    public Motd(final Combat plugin) {
        this.plugin = plugin;
    }

    /** Call after the config is available; safe to call again on reload. */
    public void load() {
        enabled = plugin.getConfig().getBoolean("motd.enabled", false);
        lines = plugin.getConfig().getStringList("motd.lines");
        centerWidth = plugin.getConfig().getInt("motd.center-width", 270);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(final ServerListPingEvent event) {
        if (!enabled || lines.isEmpty()) return;
        final TagResolver counts = TagResolver.resolver(
                Placeholder.unparsed("online", Integer.toString(event.getNumPlayers())),
                Placeholder.unparsed("max", Integer.toString(event.getMaxPlayers())));
        event.motd(Component.join(JoinConfiguration.newlines(),
                lines.stream().map(line -> render(line, centerWidth, counts)).toList()));
    }

    /** One configured line as the client will see it. Package-private so the test can drive it. */
    static Component render(final String line, final int centerWidth, final TagResolver... resolvers) {
        final boolean center = line.startsWith(CENTER);
        final Component parsed = MM.deserialize(center ? line.substring(CENTER.length()) : line, resolvers);
        if (!center) return parsed;
        // Rounded, not floored: padding only moves in whole 4px spaces, and flooring every
        // line costs up to 3px that all comes off the left.
        final int pad = Math.round((centerWidth - width(parsed, false)) / 2f / SPACE_PX);
        return pad <= 0 ? parsed : Component.text(" ".repeat(pad)).append(parsed);
    }

    /**
     * Pixel width of a parsed line.
     *
     * Walked as a tree rather than measured off the flattened text because bold glyphs are a
     * pixel wider, and a gradient hands back one child component per character -- so whether a
     * given character is bold is only knowable with the style it inherited on the way down.
     */
    static int width(final Component component, final boolean inheritedBold) {
        final TextDecoration.State own = component.decoration(TextDecoration.BOLD);
        final boolean bold = own == TextDecoration.State.NOT_SET
                ? inheritedBold : own == TextDecoration.State.TRUE;

        int px = 0;
        if (component instanceof TextComponent text) {
            for (final char c : text.content().toCharArray()) {
                // A bold space is the one glyph the game does not widen.
                px += WIDTHS.getOrDefault(c, DEFAULT_PX) + (bold && c != ' ' ? 1 : 0);
            }
        }
        for (final Component child : component.children()) px += width(child, bold);
        return px;
    }
}
