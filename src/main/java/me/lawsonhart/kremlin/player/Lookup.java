package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Durations;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import me.lawsonhart.kremlin.punish.Punishments;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.BanEntry;
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
 * /seen, /whois, /realname and /list: the ones that only read.
 *
 * /seen leans on what the server already tracks rather than keeping a login table of its own --
 * {@code getLastSeen()} and {@code getLastLogin()} are maintained for every player whether or not
 * this plugin was installed when they last logged in, which a table of ours would not be.
 *
 * The detail block under /seen and /whois owns none of what it shows: homes are {@code Homes}',
 * punishments are {@code Punishments}', the team is SimpleTeams' via {@code TeamHook} and the
 * Discord name is {@code Links}'. Any of them being absent, or the viewer not being allowed to
 * see it, costs a line rather than the command.
 */
public final class Lookup implements CommandExecutor, TabCompleter {

    private static final Set<String> SEEN = Set.of("seen", "eseen");
    private static final Set<String> WHOIS = Set.of("whois", "ewhois");
    private static final Set<String> REALNAME = Set.of("realname", "erealname");
    private static final Set<String> PLAYTIME = Set.of("playtime", "eplaytime", "played", "ontime");
    private static final Set<String> PING = Set.of("ping", "eping", "latency");

    /** The two commands that mean yourself when nobody is named. */
    private static boolean aboutYou(final String cmd) {
        return PLAYTIME.contains(cmd) || PING.contains(cmd);
    }

    /** Enough of a record to see what kind of trouble somebody is, without the whole file. */
    private static final int HOVER_HISTORY = 5;

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
        if (!SEEN.contains(cmd) && !WHOIS.contains(cmd) && !REALNAME.contains(cmd)
                && !aboutYou(cmd)) {
            return list(sender);
        }

        // /playtime and /ping with no argument are about yourself, which is the common case and
        // needs no second permission; naming somebody else does. Asking about your own is the
        // plain node either way, so the .others one is never demanded of somebody who only ever
        // had that -- which is what Essentials' own essentials.playtime always covered.
        final boolean self = aboutYou(cmd) && args.length == 0;
        final String base = PING.contains(cmd) ? "ping" : "playtime";
        final String node = SEEN.contains(cmd) ? "seen"
                : WHOIS.contains(cmd) ? "whois"
                : REALNAME.contains(cmd) ? "realname"
                : self ? base : base + ".others";
        if (!Perms.may(sender, "kremlin." + node, "essentials." + node)) {
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
                        : REALNAME.contains(cmd) ? realname(target)
                        : PING.contains(cmd) ? ping(target, self)
                        : playtime(target, self)));
        return true;
    }

    /**
     * /ping [player]: the round trip the server already measures for its keep-alive packets.
     *
     * Nothing is timed here. {@code getPing()} is that running average, which is both cheaper
     * and steadier than sending a packet of our own and waiting for it to come back -- and it is
     * the same number the vanilla tab list draws its signal bars from.
     */
    private Component ping(final OfflinePlayer target, final boolean self) {
        final Player online = target.getPlayer();
        if (online == null) {
            return plugin.msg("ping-offline",
                    Placeholder.component("player", plugin.displayName(target)));
        }
        final int ms = online.getPing();
        return plugin.msg(self ? "ping-self" : "ping-other",
                Placeholder.component("player", online.displayName()),
                Placeholder.unparsed("name", online.getName()),
                Placeholder.unparsed("ms", String.valueOf(ms)),
                // Our own constant, never anything typed, so parsing it as a tag is safe.
                Placeholder.parsed("colour", colour(ms)));
    }

    /** How the number reads at a glance. The bands are the tab list's own signal bars. */
    static String colour(final int ms) {
        if (ms < 0) return "<gray>";
        return ms < 150 ? "<green>" : ms < 300 ? "<yellow>" : "<red>";
    }

    /**
     * /realname &lt;nickname&gt;: the account behind a nickname.
     *
     * The whole lookup is {@link Combat#findPlayer} already -- it tries real names, then online
     * nicknames, then stored ones -- so this is that answer read back the other way round.
     */
    private Component realname(final OfflinePlayer target) {
        return plugin.msg("realname",
                Placeholder.component("player", name(target)),
                Placeholder.unparsed("name", String.valueOf(target.getName())));
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
        final Component header = online != null
                ? plugin.msg("seen-online", Placeholder.component("player", name),
                        Placeholder.unparsed("time", PlayerList.ago(online.getLastLogin())),
                        Placeholder.unparsed("extra", extra(sender, target)))
                : plugin.msg("seen-offline", Placeholder.component("player", name),
                        Placeholder.unparsed("time", PlayerList.ago(target.getLastSeen())),
                        Placeholder.unparsed("extra", extra(sender, target)));
        return report(header, sender, target, false);
    }

    private Component whois(final CommandSender sender, final OfflinePlayer target) {
        final Player online = target.getPlayer();
        final Component header = online == null
                ? plugin.msg("whois-offline", Placeholder.component("player", name(target)),
                        Placeholder.unparsed("name", String.valueOf(target.getName())),
                        Placeholder.unparsed("time", PlayerList.ago(target.getLastSeen())),
                        Placeholder.unparsed("uuid", target.getUniqueId().toString()))
                : plugin.msg("whois-online", Placeholder.component("player", online.displayName()),
                        Placeholder.unparsed("name", online.getName()),
                        Placeholder.unparsed("uuid", online.getUniqueId().toString()),
                        Placeholder.unparsed("world", online.getWorld().getName()),
                        Placeholder.unparsed("health", String.valueOf(Math.round(online.getHealth()))),
                        Placeholder.unparsed("gamemode", online.getGameMode().name().toLowerCase(Locale.ROOT)),
                        Placeholder.unparsed("extra", extra(sender, target)));
        return report(header, sender, target, true);
    }

    // ---------------------------------------------------------------- the detail block

    /** The header, then whatever detail lines this viewer is allowed to see, then the buttons. */
    private Component report(final Component header, final CommandSender sender,
                             final OfflinePlayer target, final boolean full) {
        final List<Component> lines = new ArrayList<>();
        lines.add(header);
        details(lines, sender, target, full);
        final Component buttons = buttons(sender, target);
        if (buttons != null) lines.add(buttons);
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * Team, homes, punishments and the rest. {@code full} is /whois -- /seen gets the same block
     * minus the identifiers, which are what you run /whois for in the first place.
     *
     * Every line that can be clicked runs a command the viewer already has the permission for,
     * because the same node was checked to decide whether to show the line at all.
     */
    private void details(final List<Component> out, final CommandSender sender,
                         final OfflinePlayer target, final boolean full) {
        final UUID id = target.getUniqueId();
        final String name = target.getName() == null ? id.toString() : target.getName();

        if (full) {
            out.add(chip("whois-line-uuid", plugin.msg("whois-hover-uuid"),
                    ClickEvent.copyToClipboard(id.toString()),
                    Placeholder.unparsed("uuid", id.toString())));
        }

        final String team = plugin.getTeamHook().teamNameOf(id);
        out.add(team == null
                ? plugin.msg("whois-line-team-none")
                : plugin.msg("whois-line-team",
                        Placeholder.component("team", PlayerList.legacy(team))));

        if (Perms.may(sender, "kremlin.home.others", "combatprev.home.others", "essentials.home.others")) {
            final List<String> homes = plugin.getHomes().setHomesOf(id);
            out.add(homes.isEmpty()
                    ? plugin.msg("whois-line-homes-none")
                    : chip("whois-line-homes",
                            plugin.msg("whois-hover-homes", Placeholder.unparsed("name", name),
                                    Placeholder.unparsed("homes", String.join(", ", homes))),
                            ClickEvent.runCommand("/adminhome " + name),
                            Placeholder.unparsed("count", String.valueOf(homes.size()))));
        }

        if (Perms.may(sender, "kremlin.history", "essentials.history")) {
            final List<Punishments.Entry> history = plugin.getPunishments().historyOf(id);
            out.add(history.isEmpty()
                    ? plugin.msg("whois-line-punishments-none")
                    : chip("whois-line-punishments",
                            plugin.msg("whois-hover-punishments",
                                    Placeholder.component("recent", recent(history))),
                            ClickEvent.runCommand("/history " + name),
                            Placeholder.unparsed("count", String.valueOf(history.size()))));
            punished(out, target);
        }

        if (full && Perms.may(sender, "kremlin.alts", "essentials.alts")) {
            final int alts = plugin.getPunishments().altsOf(id).size();
            if (alts > 0) {
                out.add(chip("whois-line-alts", plugin.msg("whois-hover-alts"),
                        ClickEvent.runCommand("/alts " + name),
                        Placeholder.unparsed("count", String.valueOf(alts))));
            }
        }

        if (full) {
            final String discord = plugin.getPlayers().discordOf(id);
            if (discord != null) {
                out.add(plugin.msg("whois-line-discord", Placeholder.unparsed("discord", discord)));
            }
        }

        final long played = playedMillis(target);
        if (played > 0) {
            out.add(chip("whois-line-playtime", null, ClickEvent.runCommand("/playtime " + name),
                    Placeholder.unparsed("time", Durations.describe(played))));
        }
    }

    /** The mute and the ban, each shown only while it is actually in force. */
    private void punished(final List<Component> out, final OfflinePlayer target) {
        final Punishments.Entry mute = plugin.getPunishments().muteOf(target.getUniqueId());
        if (mute != null) {
            out.add(plugin.msg("whois-line-muted",
                    Placeholder.unparsed("time", Durations.describe(mute.remaining())),
                    Placeholder.unparsed("reason", mute.reason())));
        }
        final BanEntry<?> ban = plugin.getPunishCommands().banEntry(target);
        if (ban != null) {
            out.add(plugin.msg("whois-line-banned",
                    Placeholder.unparsed("reason", String.valueOf(ban.getReason())),
                    Placeholder.unparsed("by", String.valueOf(ban.getSource()))));
        }
    }

    /** The last few entries, for the punishment line's tooltip. */
    private Component recent(final List<Punishments.Entry> history) {
        final List<Component> lines = new ArrayList<>();
        for (final Punishments.Entry entry : history.subList(0, Math.min(HOVER_HISTORY, history.size()))) {
            lines.add(plugin.msg("history-line",
                    Placeholder.unparsed("type", entry.type()),
                    Placeholder.unparsed("by", entry.by()),
                    Placeholder.unparsed("reason", entry.reason()),
                    Placeholder.unparsed("time", PlayerList.ago(entry.at()))));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * The row of buttons. Console gets none -- it cannot click -- and neither does a viewer with
     * no permission for any of them, which is why an empty row is null rather than a blank line.
     */
    private Component buttons(final CommandSender sender, final OfflinePlayer target) {
        if (!(sender instanceof Player viewer)) return null;
        final String name = target.getName();
        if (name == null) return null;
        final boolean online = target.getPlayer() != null;
        final boolean themselves = target.getUniqueId().equals(viewer.getUniqueId());

        final List<Component> row = new ArrayList<>();
        if (online && !themselves) {
            row.add(button("whois-button-tpa", ClickEvent.runCommand("/tpa " + name), name));
            row.add(button("whois-button-msg", ClickEvent.suggestCommand("/msg " + name + " "), name));
        }
        // Both of these teleport somebody, so neither is offered for a player who is not on.
        if (online && Perms.may(viewer, "kremlin.tp", "essentials.tp")) {
            row.add(button("whois-button-tpto", ClickEvent.runCommand("/tp " + name), name));
        }
        if (online && Perms.may(viewer, "kremlin.tphere", "essentials.tphere")) {
            row.add(button("whois-button-bring", ClickEvent.runCommand("/tphere " + name), name));
        }
        if (Perms.may(viewer, "kremlin.invsee", "essentials.invsee")) {
            row.add(button("whois-button-inv", ClickEvent.runCommand("/invsee " + name), name));
        }
        if (Perms.may(viewer, "kremlin.punish", "essentials.punish")) {
            row.add(button("whois-button-punish", ClickEvent.runCommand("/punish " + name), name));
        }
        if (row.isEmpty()) return null;
        return plugin.msg("whois-buttons", Placeholder.component("buttons",
                Component.join(JoinConfiguration.separator(Component.space()), row)));
    }

    /** A detail line, with whatever of a tooltip and a click it has. */
    private Component chip(final String key, final Component hover, final ClickEvent click,
                           final TagResolver... args) {
        Component line = plugin.msg(key, args);
        if (hover != null) line = line.hoverEvent(HoverEvent.showText(hover));
        return click == null ? line : line.clickEvent(click);
    }

    /** A button. Its tooltip is its own key with {@code -hover} on the end, so it cannot drift. */
    private Component button(final String key, final ClickEvent click, final String name) {
        return plugin.msg(key).clickEvent(click).hoverEvent(HoverEvent.showText(
                plugin.msg(key + "-hover", Placeholder.unparsed("name", name))));
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
        if (args.length != 1 || (!SEEN.contains(cmd) && !WHOIS.contains(cmd)
                && !REALNAME.contains(cmd) && !aboutYou(cmd))) {
            return List.of();
        }
        // /realname is asked about a nickname and /ping about a connection, so both are only
        // ever answerable for somebody who is on right now. Offering an offline name there
        // suggests an answer the command would then have to refuse.
        return Names.filter(args[0], REALNAME.contains(cmd) || PING.contains(cmd)
                ? Names.online(plugin.getServer(), List.of())
                : Names.online(plugin.getServer(), Names.offlineNames(plugin.getServer())));
    }
}
