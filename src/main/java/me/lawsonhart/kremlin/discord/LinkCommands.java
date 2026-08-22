package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The in-game half: /link, /unlink, and /discord.
 *
 * /discord keeps its old job of printing the invite when used on its own, so nothing a player
 * already knows changes; with a name after it, and the permission for it, it answers "who is
 * this on Discord" instead.
 */
public final class LinkCommands implements CommandExecutor, TabCompleter {

    private final Combat plugin;
    private final Links links;
    private final Bot bot;
    private final RoleSync roleSync;

    public LinkCommands(final Combat plugin, final Links links, final Bot bot, final RoleSync roleSync) {
        this.plugin = plugin;
        this.links = links;
        this.bot = bot;
        this.roleSync = roleSync;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if ("discord".equals(cmd)) return lookup(sender, args);
        if ("resync".equals(cmd)) return resync(sender, args);

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- there is nothing to link."));
            return true;
        }
        if (!bot.ready()) {
            p.sendMessage(plugin.msg("discord-off"));
            return true;
        }
        if (!Perms.may(p, "kremlin.link")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        return "unlink".equals(cmd) ? unlink(p) : link(p, args);
    }

    private boolean link(final Player p, final String[] args) {
        if (args.length > 0) {
            // They have a code from Discord: finish it here.
            final Links.Result result = links.redeemInGame(p.getUniqueId(), args[0]);
            p.sendMessage(plugin.msg(messageFor(result), Placeholder.unparsed("code", args[0])));
            // Roles are the point of linking, so they arrive with it rather than at the next pass.
            if (result == Links.Result.LINKED) {
                roleSync.sync(p.getUniqueId());
                plugin.getDiscordLog().linked(p.getUniqueId(), links.discordOf(p.getUniqueId()));
            }
            return true;
        }
        if (links.isLinked(p.getUniqueId())) {
            p.sendMessage(plugin.msg("link-already"));
            return true;
        }
        // No code: start here and let them finish in Discord.
        final String code = links.startFromGame(p.getUniqueId());
        p.sendMessage(plugin.msg("link-code", Placeholder.unparsed("code", code)));
        return true;
    }

    private boolean unlink(final Player p) {
        final long discord = links.discordOf(p.getUniqueId());
        if (!links.isLinked(p.getUniqueId())) {
            p.sendMessage(plugin.msg("link-none"));
            return true;
        }
        // The id has to be read before unlinking, or there is no member left to take the
        // roles off -- the mapping is the only way back to them.
        links.unlink(p.getUniqueId());
        roleSync.forget(discord);
        plugin.getDiscordLog().unlinked(p.getUniqueId(), discord);
        p.sendMessage(plugin.msg("link-removed"));
        return true;
    }

    /**
     * With no argument, a player fixes their own roles. With {@code all}, staff reconcile
     * everybody -- for when a rank changed outside the game.
     *
     * The self form is deliberately open to everyone: the usual reason somebody needs it is that
     * their roles are already wrong, and making them find a staff member to fix it is the worst
     * possible answer.
     */
    private boolean resync(final CommandSender sender, final String[] args) {
        final boolean everyone = args.length > 0
                && (args[0].equalsIgnoreCase("all") || args[0].equalsIgnoreCase("everyone"));
        if (!everyone) return resyncSelf(sender);

        if (!Perms.may(sender, "kremlin.discord.resync")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (!bot.ready()) {
            sender.sendMessage(plugin.msg("discord-off"));
            return true;
        }
        // Off-thread: this is one Discord round trip per linked player.
        org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin.owner(), t ->
                sender.sendMessage(plugin.msg("discord-resynced",
                        Placeholder.unparsed("count", String.valueOf(roleSync.syncAll())))));
        return true;
    }

    /** One player fixing their own roles, with a straight answer about what changed. */
    private boolean resyncSelf(final CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }
        if (!bot.ready()) {
            sender.sendMessage(plugin.msg("discord-off"));
            return true;
        }
        p.sendMessage(plugin.msg("resync-checking"));
        // Off-thread: a Discord round trip. The reply comes back through the callback.
        org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin.owner(), t ->
                roleSync.syncReporting(p.getUniqueId(), outcome -> p.sendMessage(switch (outcome) {
                    case "off" -> plugin.msg("discord-off");
                    case "unlinked" -> plugin.msg("resync-unlinked");
                    case "notinguild" -> plugin.msg("resync-not-in-guild");
                    case "ok" -> plugin.msg("resync-ok");
                    default -> plugin.msg("resync-fixed",
                            Placeholder.unparsed("count", outcome));
                })));
        return true;
    }

    /** /discord on its own is the invite; /discord &lt;player&gt; is a staff lookup. */
    private boolean lookup(final CommandSender sender, final String[] args) {
        if (args.length == 0) {
            plugin.getInfo().onCommand(sender, null, "discord", args);
            return true;
        }
        if (!Perms.may(sender, "kremlin.discord.lookup")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final UUID id = plugin.findPlayer(args[0]);
        if (id == null) {
            sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
            return true;
        }
        final long discord = links.discordOf(id);
        final net.kyori.adventure.text.Component name =
                plugin.displayName(plugin.getServer().getOfflinePlayer(id));
        sender.sendMessage(discord == 0L
                ? plugin.msg("discord-not-linked", Placeholder.component("player", name))
                : plugin.msg("discord-linked", Placeholder.component("player", name),
                        Placeholder.unparsed("id", String.valueOf(discord))));
        return true;
    }

    private static String messageFor(final Links.Result result) {
        return switch (result) {
            case LINKED -> "link-done";
            case UNKNOWN_CODE -> "link-bad-code";
            case EXPIRED -> "link-expired";
            case WRONG_SIDE -> "link-wrong-side";
            case ALREADY_LINKED -> "link-already";
            case TARGET_TAKEN -> "link-taken";
        };
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        if (!"discord".equals(Combat.rootCommand(label)) || args.length != 1) return List.of();
        return Perms.may(sender, "kremlin.discord.lookup")
                ? Names.filter(args[0], Names.online(plugin.getServer(), Names.offlineNames(plugin.getServer()))) : List.of();
    }
}
