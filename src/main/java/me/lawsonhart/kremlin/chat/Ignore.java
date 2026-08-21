package me.lawsonhart.kremlin.chat;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import me.lawsonhart.kremlin.core.Users;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /ignore, and the list itself.
 *
 * Stored by UUID rather than by name so a rename cannot quietly un-ignore somebody. Public chat
 * is filtered by dropping the ignorer from the message's viewer set, which is cleaner than
 * cancelling anything: the sender still sees their own message land, and every other viewer is
 * untouched.
 */
public final class Ignore implements CommandExecutor, TabCompleter {

    private static final String KEY = "ignored";

    private final Combat plugin;
    private final Users users;

    public Ignore(final Combat plugin, final Users users) {
        this.plugin = plugin;
        this.users = users;
    }

    /** Staff carrying the exempt node are never ignorable, or /ignore becomes a mute for staff. */
    public boolean ignores(final UUID viewer, final UUID sender) {
        if (viewer.equals(sender)) return false;
        final Player other = plugin.getServer().getPlayer(sender);
        if (other != null && Perms.may(other, "kremlin.ignore.exempt", "essentials.ignore.exempt")) return false;
        return users.list(viewer, KEY).contains(sender.toString());
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- the console cannot ignore anybody."));
            return true;
        }
        if (!Perms.may(p, "kremlin.ignore", "essentials.ignore")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            listFor(p);
            return true;
        }

        final UUID target = plugin.findPlayer(args[0]);
        if (target == null) {
            p.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
            return true;
        }
        if (target.equals(p.getUniqueId())) {
            p.sendMessage(plugin.msg("ignore-self"));
            return true;
        }

        final OfflinePlayer other = plugin.getServer().getOfflinePlayer(target);
        final String name = other.getName() == null ? args[0] : other.getName();
        final Set<String> ignored = new LinkedHashSet<>(users.list(p.getUniqueId(), KEY));
        final boolean added = ignored.add(target.toString());
        if (!added) ignored.remove(target.toString());
        users.set(p.getUniqueId(), KEY, ignored);
        p.sendMessage(plugin.msg(added ? "ignore-on" : "ignore-off",
                Placeholder.component("player", plugin.displayName(other))));
        return true;
    }

    private void listFor(final Player p) {
        final List<String> ignored = users.list(p.getUniqueId(), KEY);
        if (ignored.isEmpty()) {
            p.sendMessage(plugin.msg("ignore-none"));
            return;
        }
        final StringBuilder names = new StringBuilder();
        for (final String raw : ignored) {
            final OfflinePlayer other = plugin.getServer().getOfflinePlayer(UUID.fromString(raw));
            names.append(names.isEmpty() ? "" : ", ").append(other.getName() == null ? raw : other.getName());
        }
        p.sendMessage(plugin.msg("ignore-list", Placeholder.unparsed("players", names.toString())));
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        if (args.length != 1 || !(sender instanceof Player p)) return List.of();
        final List<String> options = Names.online(plugin.getServer(), List.of());
        options.remove(Names.plainName(p));
        return Names.filter(args[0], options);
    }
}
