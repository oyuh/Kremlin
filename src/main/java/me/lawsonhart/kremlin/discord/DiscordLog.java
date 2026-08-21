package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Durations;
import me.lawsonhart.kremlin.punish.Punishments;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.Color;
import java.time.Instant;
import java.util.UUID;

/**
 * The audit trail, mirrored into a Discord channel.
 *
 * Everything punishing already funnels through {@link Punishments#record}, so this hangs off that
 * one hook rather than off each command -- a punishment added later is logged without anyone
 * remembering to log it. Linking is only four call sites and both of them live in this package,
 * so those say so directly.
 *
 * Every send is best-effort. A missing channel, a revoked permission or a Discord outage must
 * never turn into a failed ban.
 */
public final class DiscordLog {

    private static final Color RED = new Color(0xE0_4F_5F);
    private static final Color ORANGE = new Color(0xE0_8B_3F);
    private static final Color YELLOW = new Color(0xE0_C8_4F);
    private static final Color GREEN = new Color(0x4F_C0_6F);
    private static final Color GREY = new Color(0x8A_8A_8A);

    private final Combat plugin;
    private final Bot bot;

    private String channelId = "";
    private boolean warned;

    public DiscordLog(final Combat plugin, final Bot bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    public void load() {
        channelId = plugin.getConfig().getString("discord.log-channel", "").trim();
    }

    public boolean enabled() {
        return bot.ready() && !channelId.isEmpty();
    }

    // ---------------------------------------------------------------- what gets logged

    /** Wired to {@link Punishments#onRecord}, so every punishment is covered by construction. */
    public void punishment(final Punishments.Entry entry) {
        if (!enabled()) return;
        final String type = entry.type();
        final Color colour = switch (type) {
            case "ban", "tempban" -> RED;
            case "mute" -> ORANGE;
            case "kick", "warn" -> YELLOW;
            default -> GREY;
        };

        final EmbedBuilder embed = new EmbedBuilder()
                .setColor(colour)
                .setTitle(title(type))
                .addField("Player", nameOf(entry.target()), true)
                .addField("By", entry.by(), true)
                .setTimestamp(Instant.ofEpochMilli(entry.at() == 0 ? System.currentTimeMillis() : entry.at()));

        if (entry.until() != 0L) {
            embed.addField("Length", Durations.describe(entry.remaining()), true);
        }
        if (!entry.reason().isBlank()) {
            embed.addField("Reason", field(entry.reason()), false);
        }
        send(embed);
    }

    public void linked(final UUID player, final long discordId) {
        if (!enabled()) return;
        send(new EmbedBuilder().setColor(GREEN).setTitle("Account linked")
                .addField("Player", nameOf(player), true)
                .addField("Discord", "<@" + discordId + ">", true)
                .setTimestamp(Instant.now()));
    }

    public void unlinked(final UUID player, final long discordId) {
        if (!enabled()) return;
        send(new EmbedBuilder().setColor(GREY).setTitle("Account unlinked")
                .addField("Player", nameOf(player), true)
                .addField("Discord", "<@" + discordId + ">", true)
                .setTimestamp(Instant.now()));
    }

    // ---------------------------------------------------------------- sending

    private void send(final EmbedBuilder embed) {
        final TextChannel channel = channel();
        if (channel == null) return;
        // queue(), never complete(): this can be called from a region thread and must not wait
        // on Discord. The failure callback swallows it -- a log line is not worth an exception
        // travelling back up into a ban.
        channel.sendMessageEmbeds(embed.build()).queue(null, error ->
                plugin.getLogger().warning("Could not write to the Discord log channel: " + error));
    }

    private TextChannel channel() {
        if (bot.jda() == null) return null;
        final TextChannel channel = bot.jda().getTextChannelById(channelId);
        if (channel == null && !warned) {
            warned = true;
            plugin.getLogger().warning("discord.log-channel " + channelId
                    + " is not a text channel this bot can see -- logging is off.");
        }
        return channel;
    }

    /**
     * An embed field caps at 1024 characters and JDA throws past it. A reason is free text typed
     * by staff, so this is reachable without anyone trying -- and the throw would be swallowed by
     * the record hook, quietly losing the log line for a punishment that did happen.
     */
    private static String field(final String text) {
        return text.length() <= 1024 ? text : text.substring(0, 1021) + "...";
    }

    private static String title(final String type) {
        return switch (type) {
            case "ban" -> "Banned";
            case "tempban" -> "Temporarily banned";
            case "mute" -> "Muted";
            case "kick" -> "Kicked";
            case "warn" -> "Warned";
            default -> Character.toUpperCase(type.charAt(0)) + type.substring(1);
        };
    }

    private String nameOf(final UUID player) {
        final String name = plugin.getServer().getOfflinePlayer(player).getName();
        return name == null ? player.toString() : name;
    }

    public String describe() {
        return enabled() ? "logging" : "no log channel";
    }
}
