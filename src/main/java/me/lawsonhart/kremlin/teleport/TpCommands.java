package me.lawsonhart.kremlin.teleport;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import me.lawsonhart.kremlin.core.Users;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The staff teleport suite, plus /back and /top.
 *
 * Two kinds of teleport live here and they are gated differently on purpose:
 *
 * <ul>
 *   <li>Player-facing ones -- /back and /top -- go through {@link Combat#startWarmup} exactly like
 *       /home and /warp, because both are ways out of a fight. /back especially: dying and
 *       returning to your gear mid-fight is the escape this plugin exists to close.
 *   <li>Staff ones -- /tp and friends -- move somebody instantly. Warming up an admin's /tp would
 *       be noise, and the combat gate is what actually matters, so they get that and no warmup.
 *       /tpo and /tpohere skip even that: overriding is the entire point of those two labels.
 * </ul>
 */
public final class TpCommands implements CommandExecutor, TabCompleter, Listener {

    private static final Set<String> TP = Set.of("tp", "etp", "tele", "tp2p");
    private static final Set<String> TPHERE = Set.of("tphere", "etphere", "s");
    private static final Set<String> TPO = Set.of("tpo", "etpo");
    private static final Set<String> TPOHERE = Set.of("tpohere", "etpohere");
    private static final Set<String> TPALL = Set.of("tpall", "etpall");
    private static final Set<String> TPAALL = Set.of("tpaall", "etpaall");
    private static final Set<String> TPPOS = Set.of("tppos", "etppos");
    private static final Set<String> TPTOGGLE = Set.of("tptoggle", "etptoggle");
    private static final Set<String> BACK = Set.of("back", "eback", "return");
    private static final Set<String> TOP = Set.of("top", "etop");

    /**
     * Every label this class answers to. {@link Combat} reads it to keep blocked-commands off
     * commands that already gate themselves -- see {@code Combat.ours}.
     */
    public static final Set<String> LABELS = Set.of(
            "tp", "etp", "tele", "tp2p", "tphere", "etphere", "s", "tpo", "etpo",
            "tpohere", "etpohere", "tpall", "etpall", "tpaall", "etpaall",
            "tppos", "etppos", "tptoggle", "etptoggle", "back", "eback", "return", "top", "etop");

    /** The users.yml key for /tptoggle. */
    public static final String TOGGLE = "tptoggle";
    private static final String LAST = "back";

    private final Combat plugin;
    private final Users users;
    private final Tpa tpa;

    public TpCommands(final Combat plugin, final Users users, final Tpa tpa) {
        this.plugin = plugin;
        this.users = users;
        this.tpa = tpa;
    }

    /**
     * Whether {@code target} is accepting teleports from {@code from}.
     *
     * Shared with {@link Tpa} so /tp and /tpa answer the same question the same way -- a player
     * who has turned teleports off should not find that one of the two still gets through.
     */
    public static boolean accepts(final Users users, final Player target, final CommandSender from) {
        if (users.flag(target.getUniqueId(), TOGGLE, true)) return true;
        return Perms.may(from, "kremlin.tp.override", "essentials.tp.override");
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (TPTOGGLE.contains(cmd)) return toggle(sender, args);
        if (BACK.contains(cmd)) return back(sender);
        if (TOP.contains(cmd)) return top(sender);
        if (TPPOS.contains(cmd)) return tppos(sender, args);
        if (TPAALL.contains(cmd)) return askAll(sender);
        if (TPALL.contains(cmd)) return bringAll(sender, args);

        final boolean here = TPHERE.contains(cmd) || TPOHERE.contains(cmd);
        final boolean override = TPO.contains(cmd) || TPOHERE.contains(cmd);
        final String node = override ? "tp.override" : here ? "tphere" : "tp";
        if (!Perms.may(sender, "kremlin." + node, "essentials." + node)) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("tp-usage"));
            return true;
        }

        final Player first = Names.resolve(plugin.getServer(), args[0]);
        if (first == null) {
            sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
            return true;
        }
        if (here) {
            if (!(sender instanceof Player to)) {
                sender.sendMessage(plugin.msg("console-needs-player"));
                return true;
            }
            return move(sender, first, to.getLocation(), override);
        }

        // /tp <player> [player]: with two names the FIRST is the one that moves, which is what
        // makes "/tp Bob Steve" read as "send Bob to Steve".
        if (args.length > 1) {
            if (!Perms.may(sender, "kremlin.tp.others", "essentials.tp.others")) {
                sender.sendMessage(plugin.msg("chat-no-permission"));
                return true;
            }
            final Player second = Names.resolve(plugin.getServer(), args[1]);
            if (second == null) {
                sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[1])));
                return true;
            }
            return move(sender, first, second.getLocation(), override);
        }
        if (!(sender instanceof Player mover)) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }
        return move(sender, mover, first.getLocation(), override);
    }

    // ---------------------------------------------------------------- moving people

    /**
     * The single door every staff teleport goes through: the combat gate, the tptoggle check,
     * then an instant move. Nothing here warms up -- see the class comment for why.
     */
    private boolean move(final CommandSender sender, final Player mover,
                         final Location destination, final boolean override) {
        if (!override) {
            final Component deny = plugin.denyTeleport(mover);
            if (deny != null) {
                sender.sendMessage(deny);
                return true;
            }
            if (!accepts(users, mover, sender)) {
                sender.sendMessage(plugin.msg("tp-refused",
                        Placeholder.component("player", plugin.displayName(mover))));
                return true;
            }
        }
        remember(mover);
        mover.teleportAsync(destination.clone());
        sender.sendMessage(plugin.msg("tp-sent", Placeholder.component("player", plugin.displayName(mover))));
        return true;
    }

    private boolean bringAll(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.tpall", "essentials.tpall")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final Player to = args.length > 0 ? Names.resolve(plugin.getServer(), args[0])
                : sender instanceof Player p ? p : null;
        if (to == null) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }
        int moved = 0;
        int skipped = 0;
        for (final Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.getUniqueId().equals(to.getUniqueId())) continue;
            // Per player, not per command: pulling somebody out of a fight is the escape this
            // plugin exists to stop, and one tagged player must not fail the whole command.
            if (plugin.denyTeleport(other) != null) {
                skipped++;
                continue;
            }
            remember(other);
            other.teleportAsync(to.getLocation().clone());
            moved++;
        }
        sender.sendMessage(plugin.msg("tpall", Placeholder.unparsed("count", String.valueOf(moved))));
        if (skipped > 0) {
            sender.sendMessage(plugin.msg("tpall-skipped",
                    Placeholder.unparsed("count", String.valueOf(skipped))));
        }
        return true;
    }

    private boolean askAll(final CommandSender sender) {
        if (!Perms.may(sender, "kremlin.tpaall", "essentials.tpaall")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }
        // Requests, not teleports: each one lands in the normal /tpaccept flow with its warmup.
        final int asked = tpa.askAll(p);
        sender.sendMessage(plugin.msg("tpaall", Placeholder.unparsed("count", String.valueOf(asked))));
        return true;
    }

    private boolean tppos(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.tppos", "essentials.tppos")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.msg("tppos-usage"));
            return true;
        }
        final double[] xyz = new double[3];
        for (int i = 0; i < 3; i++) {
            try {
                xyz[i] = Double.parseDouble(args[i]);
            } catch (final NumberFormatException ex) {
                sender.sendMessage(plugin.msg("tppos-usage"));
                return true;
            }
        }
        World world = p.getWorld();
        if (args.length > 5) {
            final World named = plugin.getServer().getWorld(args[5]);
            if (named == null) {
                sender.sendMessage(plugin.msg("tppos-usage"));
                return true;
            }
            world = named;
        }
        float yaw = p.getLocation().getYaw();
        float pitch = p.getLocation().getPitch();
        try {
            if (args.length > 3) yaw = Float.parseFloat(args[3]);
            if (args.length > 4) pitch = Float.parseFloat(args[4]);
        } catch (final NumberFormatException ex) {
            sender.sendMessage(plugin.msg("tppos-usage"));
            return true;
        }
        return move(sender, p, new Location(world, xyz[0], xyz[1], xyz[2], yaw, pitch), false);
    }

    /**
     * /top. getHighestBlockYAt is the platform's own answer to "what is the surface here", so
     * there is no scan to write, and it loads the column properly on this player's own region.
     */
    private boolean top(final CommandSender sender) {
        if (!Perms.may(sender, "kremlin.top", "essentials.top")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }
        final Component deny = plugin.denyTeleport(p);
        if (deny != null) {
            sender.sendMessage(deny);
            return true;
        }
        final Location at = p.getLocation();
        final Location up = at.clone();
        up.setY(at.getWorld().getHighestBlockYAt(at) + 1.0);
        if (up.getY() <= at.getY()) {
            p.sendMessage(plugin.msg("top-already"));
            return true;
        }
        remember(p);
        p.sendMessage(plugin.msg("top"));
        plugin.startWarmup(p, up, null, plugin.warmupFor(p));
        return true;
    }

    // ---------------------------------------------------------------- /back

    private boolean back(final CommandSender sender) {
        if (!Perms.may(sender, "kremlin.back", "essentials.back")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }
        final Location last = users.location(p.getUniqueId(), LAST);
        if (last == null) {
            sender.sendMessage(plugin.msg("back-nowhere"));
            return true;
        }
        final Component deny = plugin.denyTeleport(p);
        if (deny != null) {
            sender.sendMessage(deny);
            return true;
        }
        // Where they are now becomes the next /back, so it toggles between two places.
        remember(p);
        p.sendMessage(plugin.msg("back"));
        plugin.startWarmup(p, last, null, plugin.warmupFor(p));
        return true;
    }

    private void remember(final Player p) {
        users.set(p.getUniqueId(), LAST, p.getLocation().clone());
    }

    /**
     * Dying is the one everybody actually wants /back for. MONITOR because we only read where
     * they were; anything that cancels or relocates the death has already had its say.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        if (!Perms.may(event.getPlayer(), "kremlin.back.ondeath", "essentials.back.ondeath")) return;
        users.set(event.getPlayer().getUniqueId(), LAST, event.getPlayer().getLocation().clone());
    }

    /**
     * Anything else that moved them by command. Deliberately not every teleport: an ender pearl
     * or a portal is not somewhere you asked to leave, and turning those into /back targets is
     * how it ends up pointing somewhere useless.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND
                && event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        users.set(event.getPlayer().getUniqueId(), LAST, event.getFrom().clone());
    }

    // ---------------------------------------------------------------- /tptoggle

    private boolean toggle(final CommandSender sender, final String[] args) {
        final boolean others = args.length > 0 && !args[0].equalsIgnoreCase("on")
                && !args[0].equalsIgnoreCase("off");
        final String node = others ? "tptoggle.others" : "tptoggle";
        if (!Perms.may(sender, "kremlin." + node, "essentials." + node)) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final Player target;
        if (others) {
            target = Names.resolve(plugin.getServer(), args[0]);
            if (target == null) {
                sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }

        final String word = args.length > (others ? 1 : 0) ? args[others ? 1 : 0] : null;
        final boolean now = word == null
                ? !users.flag(target.getUniqueId(), TOGGLE, true)
                : word.equalsIgnoreCase("on") || word.equalsIgnoreCase("true");
        users.set(target.getUniqueId(), TOGGLE, now);
        target.sendMessage(plugin.msg(now ? "tptoggle-on" : "tptoggle-off"));
        if (!target.equals(sender)) {
            sender.sendMessage(plugin.msg(now ? "tptoggle-on-other" : "tptoggle-off-other",
                    Placeholder.component("player", plugin.displayName(target))));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (BACK.contains(cmd) || TOP.contains(cmd) || TPAALL.contains(cmd)) return List.of();
        if (TPPOS.contains(cmd)) return List.of();
        final List<String> online = Names.online(plugin.getServer(), List.of());
        if (TPTOGGLE.contains(cmd)) {
            final List<String> options = new ArrayList<>(List.of("on", "off"));
            options.addAll(online);
            return args.length == 1 ? Names.filter(args[0], options) : List.of();
        }
        if (args.length == 1) return Names.filter(args[0], online);
        return args.length == 2 && TP.contains(cmd) ? Names.filter(args[1], online) : List.of();
    }
}
