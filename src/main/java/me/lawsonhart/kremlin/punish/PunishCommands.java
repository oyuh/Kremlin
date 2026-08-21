package me.lawsonhart.kremlin.punish;

import io.papermc.paper.ban.BanListType;
import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Durations;
import me.lawsonhart.kremlin.core.Format;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import me.lawsonhart.kremlin.core.Users;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.BanEntry;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bans, mutes, kicks, warns, and the two commands that read them back.
 *
 * Bans go straight onto the server's own ban list. It already does expiry, reasons, the source
 * and the disconnect screen, and it is consulted at login before any plugin runs -- reimplementing
 * that would be a login listener, a storage format and a race, for nothing. Mutes have no such
 * thing, so those live in {@link Punishments}.
 */
public final class PunishCommands implements CommandExecutor, TabCompleter {

    private static final Set<String> BAN = Set.of("ban", "eban");
    private static final Set<String> TEMPBAN = Set.of("tempban", "etempban");
    private static final Set<String> UNBAN = Set.of("unban", "eunban", "pardon");
    private static final Set<String> BANIP = Set.of("banip", "ebanip");
    private static final Set<String> UNBANIP = Set.of("unbanip", "eunbanip", "pardonip");
    private static final Set<String> MUTE = Set.of("mute", "emute");
    private static final Set<String> UNMUTE = Set.of("unmute", "eunmute");
    private static final Set<String> KICK = Set.of("kick", "ekick");
    private static final Set<String> KICKALL = Set.of("kickall", "ekickall");
    private static final Set<String> WARN = Set.of("warn", "ewarn");
    private static final Set<String> ALTS = Set.of("alts", "ealts");
    private static final Set<String> HISTORY = Set.of("history", "punishments", "ehistory");

    private final Combat plugin;
    private final Users users;
    private final Punishments punishments;

    private long maxMute = Durations.PERMANENT;
    private long maxTempban = Durations.PERMANENT;

    public PunishCommands(final Combat plugin, final Users users, final Punishments punishments) {
        this.plugin = plugin;
        this.users = users;
        this.punishments = punishments;
    }

    public void load() {
        // EssentialsX writes these in seconds, with -1 meaning no limit.
        maxMute = seconds(plugin.getConfig().getLong("max-mute-time", -1));
        maxTempban = seconds(plugin.getConfig().getLong("max-tempban-time", -1));
    }

    private static long seconds(final long value) {
        return value < 0 ? Durations.PERMANENT : value * 1000L;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (KICKALL.contains(cmd)) return kickAll(sender, args);
        if (UNBANIP.contains(cmd)) return unbanIp(sender, args);

        final String node = node(cmd);
        if (!Perms.may(sender, "kremlin." + node, "essentials." + node)) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("punish-usage", Placeholder.unparsed("command", cmd)));
            return true;
        }

        // /banip and /unbanip take an address as readily as a name.
        if (BANIP.contains(cmd)) return banIp(sender, args);

        final UUID id = plugin.findPlayer(args[0]);
        if (id == null) {
            sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
            return true;
        }
        final OfflinePlayer target = plugin.getServer().getOfflinePlayer(id);
        final Player online = target.getPlayer();
        // Exempt is checked once, here, so every punishment respects it the same way.
        if (online != null && punishments.exempt(online) && !UNBAN.contains(cmd) && !UNMUTE.contains(cmd)
                && !ALTS.contains(cmd) && !HISTORY.contains(cmd)) {
            sender.sendMessage(plugin.msg("exempt", Placeholder.unparsed("player", args[0])));
            return true;
        }

        if (ALTS.contains(cmd)) return alts(sender, target);
        if (HISTORY.contains(cmd)) return history(sender, target);
        if (UNBAN.contains(cmd)) return unban(sender, target);
        if (UNMUTE.contains(cmd)) return unmute(sender, target);
        if (TEMPBAN.contains(cmd) || MUTE.contains(cmd)) return timed(sender, target, args, cmd);
        if (BAN.contains(cmd)) return ban(sender, target, reason(args, 1), Durations.PERMANENT);
        if (KICK.contains(cmd)) return kick(sender, target, reason(args, 1));
        return warn(sender, target, reason(args, 1));
    }

    private static String node(final String cmd) {
        if (BAN.contains(cmd)) return "ban";
        if (TEMPBAN.contains(cmd)) return "tempban";
        if (UNBAN.contains(cmd)) return "unban";
        if (BANIP.contains(cmd)) return "banip";
        if (MUTE.contains(cmd)) return "mute";
        if (UNMUTE.contains(cmd)) return "unmute";
        if (KICK.contains(cmd)) return "kick";
        if (WARN.contains(cmd)) return "warn";
        if (ALTS.contains(cmd)) return "alts";
        return "history";
    }

    private static String reason(final String[] args, final int from) {
        return args.length > from ? String.join(" ", List.of(args).subList(from, args.length)) : "";
    }

    // ---------------------------------------------------------------- bans

    /**
     * Straight onto the server's ban list, which is what makes the kick screen, the expiry and
     * the login refusal somebody else's problem. A profile ban rather than a name ban, so a
     * rename does not shed it.
     */
    private boolean ban(final CommandSender sender, final OfflinePlayer target,
                        final String reason, final long millis) {
        final String why = reason.isEmpty() ? plugin.getConfig().getString("punish.default-reason", "Banned") : reason;
        final Instant expires = millis == Durations.PERMANENT
                ? null : Instant.now().plusMillis(millis);

        plugin.getServer().getBanList(BanListType.PROFILE).addBan(
                plugin.getServer().createProfile(target.getUniqueId(),
                        target.getName() == null ? "" : target.getName()),
                why, expires, sender.getName());

        final Player online = target.getPlayer();
        if (online != null) kick(online, screen(why, millis));

        punishments.record(new Punishments.Entry(millis == Durations.PERMANENT ? "ban" : "tempban",
                target.getUniqueId(), why, sender.getName(), System.currentTimeMillis(),
                millis == Durations.PERMANENT ? Durations.PERMANENT : System.currentTimeMillis() + millis));
        announce(sender, target, millis == Durations.PERMANENT ? "banned" : "tempbanned", why, millis);
        return true;
    }

    private boolean unban(final CommandSender sender, final OfflinePlayer target) {
        plugin.getServer().getBanList(BanListType.PROFILE).pardon(
                plugin.getServer().createProfile(target.getUniqueId(),
                        target.getName() == null ? "" : target.getName()));
        sender.sendMessage(plugin.msg("unbanned",
                Placeholder.component("player", plugin.displayName(target))));
        return true;
    }

    /**
     * The argument is an address, or a player whose last address we recorded. Banning by name
     * would be the wrong tool: the point of an IP ban is that it outlives the account.
     */
    private boolean banIp(final CommandSender sender, final String[] args) {
        String ip = args[0];
        if (!ip.matches("[0-9a-fA-F.:]+")) {
            final UUID id = plugin.findPlayer(args[0]);
            final String last = id == null ? null : users.text(id, "last-ip");
            if (last == null) {
                sender.sendMessage(plugin.msg("no-ip", Placeholder.unparsed("player", args[0])));
                return true;
            }
            ip = last;
        }
        final String why = reason(args, 1);
        final InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (final UnknownHostException ex) {
            sender.sendMessage(plugin.msg("no-ip", Placeholder.unparsed("player", args[0])));
            return true;
        }
        plugin.getServer().getBanList(BanListType.IP).addBan(address,
                why.isEmpty() ? "Banned" : why, (Instant) null, sender.getName());
        sender.sendMessage(plugin.msg("banned-ip", Placeholder.unparsed("ip", ip)));

        // Anyone already on from that address goes now, or the ban does nothing until they leave.
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getAddress() != null
                    && online.getAddress().getAddress().getHostAddress().equals(ip)) {
                kick(online, screen(why.isEmpty() ? "Banned" : why, Durations.PERMANENT));
            }
        }
        return true;
    }

    private boolean unbanIp(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.unbanip", "essentials.unbanip")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("punish-usage", Placeholder.unparsed("command", "unbanip")));
            return true;
        }
        plugin.getServer().getBanList(BanListType.IP).pardon(args[0]);
        sender.sendMessage(plugin.msg("unbanned-ip", Placeholder.unparsed("ip", args[0])));
        return true;
    }

    // ---------------------------------------------------------------- timed

    /** /tempban and /mute: both are {@code <player> <duration> [reason]}. */
    private boolean timed(final CommandSender sender, final OfflinePlayer target,
                          final String[] args, final String cmd) {
        final boolean muting = MUTE.contains(cmd);
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("punish-usage", Placeholder.unparsed("command", cmd)));
            return true;
        }
        final long asked = Durations.millis(args[1]);
        if (asked == Durations.INVALID) {
            sender.sendMessage(plugin.msg("bad-duration", Placeholder.unparsed("time", args[1])));
            return true;
        }
        final long cap = muting ? maxMute : maxTempban;
        final String unlimited = muting ? "mute.unlimited" : "tempban.unlimited";
        if (!Durations.within(asked, cap)
                && !Perms.may(sender, "kremlin." + unlimited, "essentials." + unlimited)) {
            sender.sendMessage(plugin.msg("too-long", Placeholder.unparsed("time", Durations.describe(cap))));
            return true;
        }

        final String why = reason(args, 2);
        if (!muting) return ban(sender, target, why, asked);

        punishments.mute(target.getUniqueId(), asked, why, sender.getName());
        final Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(asked == Durations.PERMANENT
                    ? plugin.msg("muted-forever", Placeholder.unparsed("reason", why))
                    : plugin.msg("muted", Placeholder.unparsed("reason", why),
                            Placeholder.unparsed("time", Durations.describe(asked))));
        }
        announce(sender, target, "muted-by", why, asked);
        return true;
    }

    private boolean unmute(final CommandSender sender, final OfflinePlayer target) {
        if (!punishments.unmute(target.getUniqueId())) {
            sender.sendMessage(plugin.msg("not-muted",
                    Placeholder.component("player", plugin.displayName(target))));
            return true;
        }
        final Player online = target.getPlayer();
        if (online != null) online.sendMessage(plugin.msg("unmuted-you"));
        sender.sendMessage(plugin.msg("unmuted",
                Placeholder.component("player", plugin.displayName(target))));
        return true;
    }

    // ---------------------------------------------------------------- kick, warn

    private boolean kick(final CommandSender sender, final OfflinePlayer target, final String reason) {
        final Player online = target.getPlayer();
        if (online == null) {
            sender.sendMessage(plugin.msg("not-online",
                    Placeholder.component("player", plugin.displayName(target))));
            return true;
        }
        final String why = reason.isEmpty() ? "Kicked" : reason;
        kick(online, screen(why, 0));
        punishments.record(new Punishments.Entry("kick", target.getUniqueId(), why,
                sender.getName(), System.currentTimeMillis(), 0));
        announce(sender, target, "kicked", why, 0);
        return true;
    }

    private boolean kickAll(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.kickall", "essentials.kickall")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final String why = reason(args, 0);
        int kicked = 0;
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.equals(sender) || punishments.exempt(online)) continue;
            kick(online, screen(why.isEmpty() ? "Kicked" : why, 0));
            kicked++;
        }
        sender.sendMessage(plugin.msg("kickall", Placeholder.unparsed("count", String.valueOf(kicked))));
        return true;
    }

    private boolean warn(final CommandSender sender, final OfflinePlayer target, final String reason) {
        if (reason.isEmpty()) {
            sender.sendMessage(plugin.msg("punish-usage", Placeholder.unparsed("command", "warn")));
            return true;
        }
        punishments.record(new Punishments.Entry("warn", target.getUniqueId(), reason,
                sender.getName(), System.currentTimeMillis(), 0));
        final Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(plugin.msg("warned-you", Placeholder.unparsed("reason", reason)));
        }
        announce(sender, target, "warned", reason, 0);
        return true;
    }

    // ---------------------------------------------------------------- reading back

    private boolean alts(final CommandSender sender, final OfflinePlayer target) {
        final List<UUID> alts = punishments.altsOf(target.getUniqueId());
        if (alts.isEmpty()) {
            sender.sendMessage(plugin.msg("alts-none",
                    Placeholder.component("player", plugin.displayName(target))));
            return true;
        }
        final StringBuilder names = new StringBuilder();
        for (final UUID id : alts) {
            final String name = plugin.getServer().getOfflinePlayer(id).getName();
            names.append(names.isEmpty() ? "" : ", ").append(name == null ? id : name);
        }
        sender.sendMessage(plugin.msg("alts",
                Placeholder.component("player", plugin.displayName(target)),
                Placeholder.unparsed("name", String.valueOf(target.getName())),
                Placeholder.unparsed("count", String.valueOf(alts.size())),
                Placeholder.unparsed("players", names.toString())));
        return true;
    }

    private boolean history(final CommandSender sender, final OfflinePlayer target) {
        final List<Punishments.Entry> entries = punishments.historyOf(target.getUniqueId());
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.msg("history-none",
                    Placeholder.component("player", plugin.displayName(target))));
            return true;
        }
        sender.sendMessage(plugin.msg("history-header",
                Placeholder.component("player", plugin.displayName(target)),
                Placeholder.unparsed("name", String.valueOf(target.getName())),
                Placeholder.unparsed("count", String.valueOf(entries.size()))));
        for (final Punishments.Entry entry : entries) {
            sender.sendMessage(plugin.msg("history-line",
                    Placeholder.unparsed("type", entry.type()),
                    Placeholder.unparsed("by", entry.by()),
                    Placeholder.unparsed("reason", entry.reason()),
                    Placeholder.unparsed("time", me.lawsonhart.kremlin.player.PlayerList.ago(entry.at()))));
        }
        return true;
    }

    // ---------------------------------------------------------------- shared

    /**
     * Disconnect somebody, on the region that owns them.
     *
     * The target is very often not in the sender's region -- and with the Discord commands there
     * is no sender region at all -- so this hops onto the player's own scheduler rather than
     * assuming whoever ran the command is standing next to them.
     */
    private void kick(final Player online, final Component screen) {
        online.getScheduler().run(plugin.owner(), t -> online.kick(screen), null);
    }

    /** The disconnect screen. Staff-typed, so their formatting codes are honoured in full. */
    private Component screen(final String reason, final long millis) {
        final Component body = Format.body(reason, new Format.Allowed(true, true, true));
        return millis == 0 || millis == Durations.PERMANENT
                ? plugin.msg("screen", Placeholder.component("reason", body))
                : plugin.msg("screen-temporary", Placeholder.component("reason", body),
                        Placeholder.unparsed("time", Durations.describe(millis)));
    }

    /** Tell the staff member, and everyone holding the notify node. */
    private void announce(final CommandSender sender, final OfflinePlayer target,
                          final String key, final String reason, final long millis) {
        final Component line = plugin.msg(key,
                Placeholder.component("player", plugin.displayName(target)),
                Placeholder.unparsed("name", String.valueOf(target.getName())),
                Placeholder.unparsed("by", sender.getName()),
                Placeholder.unparsed("reason", reason),
                Placeholder.unparsed("time", Durations.describe(millis)));
        sender.sendMessage(line);
        for (final Player staff : plugin.getServer().getOnlinePlayers()) {
            if (!staff.equals(sender) && Perms.may(staff, "kremlin.punish.notify")) staff.sendMessage(line);
        }
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (args.length == 1) {
            return Names.filter(args[0], Names.online(plugin.getServer(), Names.offlineNames(plugin.getServer())));
        }
        if (args.length == 2 && (TEMPBAN.contains(cmd) || MUTE.contains(cmd))) {
            return Names.filter(args[1], new ArrayList<>(
                    List.of("10m", "1h", "6h", "1d", "7d", "30d", "perm")));
        }
        return List.of();
    }

    /** Only used by the ladder menu, which needs to know whether a ban is already in place. */
    public BanEntry<?> banEntry(final OfflinePlayer target) {
        return plugin.getServer().getBanList(BanListType.PROFILE).getBanEntry(
                plugin.getServer().createProfile(target.getUniqueId(),
                        target.getName() == null ? "" : target.getName()));
    }
}
