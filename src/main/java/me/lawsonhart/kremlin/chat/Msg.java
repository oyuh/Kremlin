package me.lawsonhart.kremlin.chat;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Format;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import me.lawsonhart.kremlin.core.Users;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /msg, /reply and /msgtoggle.
 *
 * The reply target is deliberately not persisted: "who I was talking to" stops meaning anything
 * across a restart, and Essentials' own timeout on it exists for the same reason.
 */
public final class Msg implements CommandExecutor, TabCompleter, Listener {

    public static final String TOGGLE = "msgtoggle";

    private final Combat plugin;
    private final Users users;
    private final Ignore ignore;

    /** Who each player would answer with /r, remembered only for as long as they are on. */
    private final Map<UUID, UUID> replyTo = new ConcurrentHashMap<>();

    public Msg(final Combat plugin, final Users users, final Ignore ignore) {
        this.plugin = plugin;
        this.users = users;
        this.ignore = ignore;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (TOGGLE.equals(cmd) || "messagetoggle".equals(cmd)) return toggle(sender, args);

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- the console has no reply target."));
            return true;
        }
        if (!Perms.may(p, "kremlin.msg", "essentials.msg")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }

        final boolean reply = "reply".equals(cmd) || "r".equals(cmd);
        final int textFrom = reply ? 0 : 1;
        if (args.length <= textFrom) {
            p.sendMessage(plugin.msg(reply ? "reply-usage" : "msg-usage"));
            return true;
        }

        final Player target;
        if (reply) {
            final UUID last = replyTo.get(p.getUniqueId());
            target = last == null ? null : plugin.getServer().getPlayer(last);
            if (target == null) {
                p.sendMessage(plugin.msg("reply-nobody"));
                return true;
            }
        } else {
            target = Names.resolve(plugin.getServer(), args[0]);
            if (target == null) {
                p.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
                return true;
            }
        }
        send(p, target, String.join(" ", List.of(args).subList(textFrom, args.length)));
        return true;
    }

    private void send(final Player from, final Player to, final String text) {
        if (to.getUniqueId().equals(from.getUniqueId())) {
            from.sendMessage(plugin.msg("msg-self"));
            return;
        }
        // Being ignored, or having messages off, reads the same from the sender's side on
        // purpose: telling them which it was is how you turn either into a presence check.
        final boolean refused = ignore.ignores(to.getUniqueId(), from.getUniqueId())
                || (!accepting(to) && !Perms.may(from, "kremlin.msgtoggle.override", "essentials.msgtoggle.override"));
        if (refused) {
            from.sendMessage(plugin.msg("msg-refused", Placeholder.component("player", plugin.displayName(to))));
            return;
        }

        final Format.Allowed allowed = new Format.Allowed(
                Perms.may(from, "kremlin.msg.color", "essentials.msg.color"), false, false);
        final Component body = Format.body(text, allowed);

        from.sendMessage(plugin.msg("msg-sent",
                Placeholder.component("player", to.displayName()), Placeholder.component("message", body)));
        to.sendMessage(plugin.msg("msg-received",
                Placeholder.component("player", from.displayName()), Placeholder.component("message", body)));
        replyTo.put(to.getUniqueId(), from.getUniqueId());
        replyTo.put(from.getUniqueId(), to.getUniqueId());
    }

    private boolean accepting(final Player p) {
        return users.flag(p.getUniqueId(), TOGGLE, true);
    }

    /** /msgtoggle, with no argument meaning "flip it" and on/off being explicit. */
    private boolean toggle(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only."));
            return true;
        }
        if (!Perms.may(p, "kremlin.msgtoggle", "essentials.msgtoggle")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final boolean now = args.length == 0
                ? !accepting(p)
                : args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true");
        users.set(p.getUniqueId(), TOGGLE, now);
        p.sendMessage(plugin.msg(now ? "msgtoggle-on" : "msgtoggle-off"));
        return true;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        replyTo.remove(id);
        replyTo.values().remove(id);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (TOGGLE.equals(cmd) || "messagetoggle".equals(cmd)) {
            return args.length == 1 ? Names.filter(args[0], List.of("on", "off")) : List.of();
        }
        if (args.length != 1 || "reply".equals(cmd) || "r".equals(cmd) || !(sender instanceof Player p)) {
            return List.of();
        }
        final List<String> options = Names.online(plugin.getServer(), List.of());
        options.remove(Names.plainName(p));
        return Names.filter(args[0], options);
    }
}
