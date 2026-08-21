package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.combat.Combat;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Moderation from Discord.
 *
 * Every one of these is the in-game command, run through {@link DiscordSender}: same permission
 * shape, same messages, same {@code punishments.yml} entry, with the Discord user named as the
 * source. Nothing about a punishment is implemented twice, so the two paths cannot drift.
 *
 * The command names here are a fixed list, never anything a Discord user typed -- that is what
 * makes {@link DiscordSender}'s blanket "yes" to permissions safe.
 */
public final class Moderation {

    /** Slash command -> the in-game label it runs, and the options it takes in order. */
    private record Shape(String label, List<String> options) {}

    private static final Map<String, Shape> COMMANDS = Map.of(
            "ban", new Shape("ban", List.of("player", "reason")),
            "tempban", new Shape("tempban", List.of("player", "duration", "reason")),
            "unban", new Shape("unban", List.of("player")),
            "mute", new Shape("mute", List.of("player", "duration", "reason")),
            "unmute", new Shape("unmute", List.of("player")),
            "kick", new Shape("kick", List.of("player", "reason")),
            "warn", new Shape("warn", List.of("player", "reason")),
            "history", new Shape("history", List.of("player")),
            "alts", new Shape("alts", List.of("player")));

    /** How long to wait for the server to answer before giving up on the reply. */
    private static final long ANSWER_SECONDS = 10L;

    private final Combat plugin;
    private final Bot bot;

    public Moderation(final Combat plugin, final Bot bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    static List<CommandData> definitions() {
        return List.of(
                Commands.slash("ban", "Ban a player")
                        .addOption(OptionType.STRING, "player", "Who", true)
                        .addOption(OptionType.STRING, "reason", "Why", false),
                Commands.slash("tempban", "Ban a player for a while")
                        .addOption(OptionType.STRING, "player", "Who", true)
                        .addOption(OptionType.STRING, "duration", "e.g. 2d4h, 30m, perm", true)
                        .addOption(OptionType.STRING, "reason", "Why", false),
                Commands.slash("unban", "Lift a ban")
                        .addOption(OptionType.STRING, "player", "Who", true),
                Commands.slash("mute", "Stop a player talking")
                        .addOption(OptionType.STRING, "player", "Who", true)
                        .addOption(OptionType.STRING, "duration", "e.g. 1h, perm", true)
                        .addOption(OptionType.STRING, "reason", "Why", false),
                Commands.slash("unmute", "Let a player talk again")
                        .addOption(OptionType.STRING, "player", "Who", true),
                Commands.slash("kick", "Kick a player")
                        .addOption(OptionType.STRING, "player", "Who", true)
                        .addOption(OptionType.STRING, "reason", "Why", false),
                Commands.slash("warn", "Warn a player, on the record")
                        .addOption(OptionType.STRING, "player", "Who", true)
                        .addOption(OptionType.STRING, "reason", "Why", true),
                Commands.slash("history", "A player's punishment record")
                        .addOption(OptionType.STRING, "player", "Who", true),
                Commands.slash("alts", "Accounts sharing an address with a player")
                        .addOption(OptionType.STRING, "player", "Who", true));
    }

    static boolean handles(final String name) {
        return COMMANDS.containsKey(name);
    }

    /**
     * Runs the matching in-game command and edits the reply with whatever it printed.
     *
     * The command has to run on a server thread -- it kicks players, reads the ban list and
     * writes punishments.yml -- so this defers the Discord reply, hops across, and comes back
     * with the answer. Deferring first matters: Discord gives an interaction three seconds
     * before it counts as failed.
     */
    void run(final SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        final Shape shape = COMMANDS.get(event.getName());
        final List<String> args = new ArrayList<>();
        for (final String option : shape.options()) {
            final OptionMapping value = event.getOption(option);
            if (value == null) continue;
            // A reason is free text and arrives as one option; the in-game command expects it
            // spread across the remaining arguments, which is what join/split gives back.
            args.addAll(List.of(value.getAsString().trim().split("\\s+")));
        }

        final DiscordSender sender = new DiscordSender(plugin.owner(), event.getUser().getName());
        final CompletableFuture<String> answer = new CompletableFuture<>();
        bot.onServerThread(() -> {
            try {
                plugin.getPunishCommands().onCommand(sender, null, shape.label(),
                        args.toArray(new String[0]));
                answer.complete(sender.reply());
            } catch (final Throwable t) {
                plugin.getLogger().warning("Discord /" + event.getName() + " failed: " + t);
                answer.complete("That failed. Check the server log.");
            }
        });

        answer.orTimeout(ANSWER_SECONDS, TimeUnit.SECONDS)
                .exceptionally(t -> "The server did not answer in time.")
                .thenAccept(text -> event.getHook().editOriginal(trim(text)).queue());
    }

    /** Discord refuses a message over 2000 characters, and /history can be long. */
    private static String trim(final String text) {
        if (text.length() <= 1900) return text;
        return text.substring(0, 1900) + "\n… (truncated, run it in game for the rest)";
    }
}
