package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.combat.Combat;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The Discord half of linking.
 *
 * Everything here runs on a JDA thread. Reading {@link Links} is safe from anywhere -- it is a
 * plain map over users.yml -- but anything reaching into the server goes through
 * {@link Bot#onServerThread}. Replies are ephemeral: a link code is a one-time secret and does
 * not belong in a public channel.
 */
public final class SlashCommands extends ListenerAdapter {

    private final Combat plugin;
    private final Links links;
    private final Bot bot;
    private final RoleSync roleSync;
    private final Moderation moderation;

    public SlashCommands(final Combat plugin, final Links links, final Bot bot, final RoleSync roleSync) {
        this.plugin = plugin;
        this.links = links;
        this.bot = bot;
        this.roleSync = roleSync;
        this.moderation = new Moderation(plugin, bot);
    }

    /** Linking, open to everybody. */
    private static List<CommandData> linkDefinitions() {
        return List.of(
                Commands.slash("link", "Link your Minecraft account")
                        .addOption(OptionType.STRING, "code", "The code from /link in game", false),
                Commands.slash("unlink", "Unlink your Minecraft account"),
                Commands.slash("whoami", "Show which Minecraft account you are linked to"),
                Commands.slash("resync", "Reconcile every linked member's roles (staff)"));
    }

    /**
     * Everything the bot registers. Moderation is kept as its own list so the staff gate can be
     * driven off {@link Moderation#handles} rather than a second copy of the names.
     */
    static List<CommandData> definitions() {
        final List<CommandData> all = new java.util.ArrayList<>(linkDefinitions());
        all.addAll(Moderation.definitions());
        return all;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull final SlashCommandInteractionEvent event) {
        try {
            switch (event.getName()) {
                case "link" -> link(event);
                case "unlink" -> unlink(event);
                case "whoami" -> whoami(event);
                case "resync" -> resync(event);
                default -> {
                    if (!Moderation.handles(event.getName())) {
                        event.reply("Unknown command.").setEphemeral(true).queue();
                    } else if (!isStaff(event)) {
                        event.reply("You are not allowed to do that.").setEphemeral(true).queue();
                    } else {
                        moderation.run(event);
                    }
                }
            }
        } catch (final Throwable t) {
            // A slash command that throws leaves Discord showing "the application did not
            // respond", which tells nobody anything. Answer, then log it properly.
            plugin.getLogger().warning("Discord command /" + event.getName() + " failed: " + t);
            if (!event.isAcknowledged()) {
                event.reply("Something went wrong. Ask an admin to check the server log.")
                        .setEphemeral(true).queue();
            }
        }
    }

    private void link(final SlashCommandInteractionEvent event) {
        final long discordId = event.getUser().getIdLong();
        final var option = event.getOption("code");

        if (option == null) {
            if (links.playerOf(discordId) != null) {
                event.reply("You are already linked. Use `/unlink` first.").setEphemeral(true).queue();
                return;
            }
            final String code = links.startFromDiscord(discordId);
            event.reply("Run this in game within the next few minutes:\n```\n/link " + code + "\n```")
                    .setEphemeral(true).queue();
            return;
        }

        final Links.Result result = links.redeemInDiscord(discordId, option.getAsString());
        event.reply(describe(result, discordId)).setEphemeral(true).queue();
    }

    private void unlink(final SlashCommandInteractionEvent event) {
        final UUID player = links.playerOf(event.getUser().getIdLong());
        if (player == null) {
            event.reply("You are not linked to a Minecraft account.").setEphemeral(true).queue();
            return;
        }
        links.unlink(player);
        roleSync.forget(event.getUser().getIdLong());
        plugin.getDiscordLog().unlinked(player, event.getUser().getIdLong());
        event.reply("Unlinked.").setEphemeral(true).queue();
    }

    /**
     * Staff only, by Discord role. There is no Minecraft permission to lean on here -- the
     * person running it may not even be linked -- so the configured role is the whole gate.
     */
    private void resync(final SlashCommandInteractionEvent event) {
        if (!isStaff(event)) {
            event.reply("You are not allowed to do that.").setEphemeral(true).queue();
            return;
        }
        // A round trip per linked member, so answer first and report when it is done.
        event.deferReply(true).queue();
        final int done = roleSync.syncAll();
        event.getHook().editOriginal("Reconciled **" + done + "** linked member(s).").queue();
    }

    /** True when the caller holds the configured staff role. Blank config means nobody does. */
    private boolean isStaff(final SlashCommandInteractionEvent event) {
        final String role = bot.staffRole();
        if (role.isEmpty() || event.getMember() == null) return false;
        return event.getMember().getRoles().stream().anyMatch(r -> r.getId().equals(role));
    }

    private void whoami(final SlashCommandInteractionEvent event) {
        final UUID player = links.playerOf(event.getUser().getIdLong());
        if (player == null) {
            event.reply("You are not linked. Use `/link` to start.").setEphemeral(true).queue();
            return;
        }
        event.reply("You are linked to **" + nameOf(player) + "**.").setEphemeral(true).queue();
    }

    private String describe(final Links.Result result, final long discordId) {
        return switch (result) {
            case LINKED -> {
                final UUID linked = links.playerOf(discordId);
                roleSync.sync(linked);
                plugin.getDiscordLog().linked(linked, discordId);
                yield "Linked to **" + nameOf(linked) + "**.";
            }
            case UNKNOWN_CODE -> "That code does not exist. Run `/link` in game to get one.";
            case EXPIRED -> "That code has expired. Run `/link` in game for a new one.";
            case WRONG_SIDE -> "That code was made here. Type it **in game** with `/link <code>`.";
            case ALREADY_LINKED -> "You are already linked. Use `/unlink` first.";
            case TARGET_TAKEN -> "That Minecraft account is already linked to someone else.";
        };
    }

    /**
     * Safe from a JDA thread: this reads the server's player cache rather than touching a live
     * entity, which is the line {@link Bot#onServerThread} exists to protect.
     */
    private String nameOf(final UUID player) {
        if (player == null) return "unknown";
        final OfflinePlayer offline = plugin.getServer().getOfflinePlayer(player);
        return offline.getName() == null ? player.toString() : offline.getName();
    }
}
