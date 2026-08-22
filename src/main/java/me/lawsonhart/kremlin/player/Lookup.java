package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Durations;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * /seen, /whois and /list: the three that only read.
 *
 * /seen leans on what the server already tracks rather than keeping a login table of its own --
 * {@code getLastSeen()} and {@code getLastLogin()} are maintained for every player whether or not
 * this plugin was installed when they last logged in, which a table of ours would not be.
 */
public final class Lookup implements CommandExecutor, TabCompleter {

    private static final Set<String> SEEN = Set.of("seen", "eseen");
    private static final Set<String> WHOIS = Set.of("whois", "ewhois");
    private static final Set<String> PLAYTIME = Set.of("playtime", "eplaytime", "played", "ontime");

    private final Combat plugin;
    private final Nicknames nicknames;

    public Lookup(final Combat plugin, final Nicknames nicknames) {
        this.plugin = plugin;
        this.nicknames = nicknames;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (!SEEN.contains(cmd) && !WHOIS.contains(cmd) && !PLAYTIME.contains(cmd)) return list(sender);

        // /playtime with no argument is about yourself, which is the common case and needs no
        // second permission; naming somebody else does.
        final boolean self = PLAYTIME.contains(cmd) && args.length == 0;
        final String node = SEEN.contains(cmd) ? "seen"
                : WHOIS.contains(cmd) ? "whois"
                : self ? "playtime" : "playtime.others";
        if (PLAYTIME.contains(cmd) && !Perms.may(sender, "kremlin." + node, "essentials." + node)
                // essentials.playtime alone has always covered your own; don't demand the .others
                // node from somebody who only ever had the plain one.
                && !(self && Perms.may(sender, "kremlin.playtime", "essentials.playtime"))) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (!PLAYTIME.contains(cmd) && !Perms.may(sender, "kremlin." + node, "essentials." + node)) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0 && !self) {
            sender.sendMessage(plugin.msg(node + "-usage"));
            return true;
        }
        if (self && !(sender instanceof Player)) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }

        final UUID id = self ? ((Player) sender).getUniqueId() : plugin.findPlayer(args[0]);
        final OfflinePlayer target = id == null ? null : plugin.getServer().getOfflinePlayer(id);
        if (target == null || !PlayerCommands.known(target)) {
            sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
            return true;
        }
        // Reading an offline player's record can touch disk, so keep it off the region thread.
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> sender.sendMessage(
                SEEN.contains(cmd) ? seen(sender, target)
                        : WHOIS.contains(cmd) ? whois(sender, target)
                        : playtime(target, self)));
        return true;
    }

    /**
     * How long they have been on the server.
     *
     * The number is vanilla's own PLAY_ONE_MINUTE statistic, which despite the name counts
     * ticks -- so it is right even for time played before this plugin existed, which a counter
     * of our own could never be.
     */
    private Component playtime(final OfflinePlayer target, final boolean self) {
        final long millis = playedMillis(target);
        if (millis <= 0L) {
            return plugin.msg(self ? "playtime-none-self" : "playtime-none",
                    Placeholder.component("player", plugin.displayName(target)));
        }
        return plugin.msg(self ? "playtime-self" : "playtime-other",
                Placeholder.component("player", plugin.displayName(target)),
                Placeholder.unparsed("name", String.valueOf(target.getName())),
                Placeholder.unparsed("time", Durations.describe(millis)));
    }

    /** Milliseconds played, or 0 when the server has no statistics for them. */
    public static long playedMillis(final OfflinePlayer target) {
        try {
            // Ticks, not minutes. 20 per second.
            return Math.max(0L, (long) target.getStatistic(Statistic.PLAY_ONE_MINUTE)) * 50L;
        } catch (final Throwable t) {
            // No statistics file yet, or a server that will not answer for an offline player.
            return 0L;
        }
    }

    private Component seen(final CommandSender sender, final OfflinePlayer target) {
        final Player online = target.getPlayer();
        final Component name = name(target);
        if (online != null) {
            return plugin.msg("seen-online", Placeholder.component("player", name),
                    Placeholder.unparsed("time", PlayerList.ago(online.getLastLogin())),
                    Placeholder.unparsed("extra", extra(sender, target)));
        }
        return plugin.msg("seen-offline", Placeholder.component("player", name),
                Placeholder.unparsed("time", PlayerList.ago(target.getLastSeen())),
                Placeholder.unparsed("extra", extra(sender, target)));
    }

    private Component whois(final CommandSender sender, final OfflinePlayer target) {
        final Player online = target.getPlayer();
        if (online == null) {
            return plugin.msg("whois-offline", Placeholder.component("player", name(target)),
                    Placeholder.unparsed("name", String.valueOf(target.getName())),
                    Placeholder.unparsed("time", PlayerList.ago(target.getLastSeen())),
                    Placeholder.unparsed("uuid", target.getUniqueId().toString()));
        }
        return plugin.msg("whois-online", Placeholder.component("player", online.displayName()),
                Placeholder.unparsed("name", online.getName()),
                Placeholder.unparsed("uuid", online.getUniqueId().toString()),
                Placeholder.unparsed("world", online.getWorld().getName()),
                Placeholder.unparsed("health", String.valueOf(Math.round(online.getHealth()))),
                Placeholder.unparsed("gamemode", online.getGameMode().name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("extra", extra(sender, target)));
    }

    /** The IP, which is staff-only and therefore behind its own node, or nothing. */
    private String extra(final CommandSender sender, final OfflinePlayer target) {
        if (!Perms.may(sender, "kremlin.seen.extra", "essentials.seen.extra")) return "";
        final Player online = target.getPlayer();
        if (online == null || online.getAddress() == null) return "";
        return online.getAddress().getAddress().getHostAddress();
    }

    private Component name(final OfflinePlayer target) {
        final Player online = target.getPlayer();
        if (online != null) return online.displayName();
        final Component nick = nicknames.nickOf(target.getUniqueId());
        return nick != null ? nick : Component.text(String.valueOf(target.getName()));
    }

    /**
     * Plain /list: a count and the names, sorted, with nicknames shown as they appear everywhere
     * else. Deliberately not grouped by rank -- that was asked for and is what Essentials' `list:`
     * section did, but nobody has to configure anything to read a list of who is on.
     */
    private boolean list(final CommandSender sender) {
        if (!Perms.may(sender, "kremlin.list", "essentials.list")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        online.sort(Comparator.comparing(Names::plainName, String.CASE_INSENSITIVE_ORDER));

        final List<Component> names = new ArrayList<>(online.size());
        for (final Player p : online) names.add(p.displayName());
        sender.sendMessage(plugin.msg("list",
                Placeholder.unparsed("count", String.valueOf(online.size())),
                Placeholder.unparsed("max", String.valueOf(plugin.getServer().getMaxPlayers())),
                Placeholder.component("players",
                        Component.join(JoinConfiguration.separator(Component.text(", ")), names))));
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (args.length != 1
                || (!SEEN.contains(cmd) && !WHOIS.contains(cmd) && !PLAYTIME.contains(cmd))) {
            return List.of();
        }
        return Names.filter(args[0], Names.online(plugin.getServer(), Names.offlineNames(plugin.getServer())));
    }
}
