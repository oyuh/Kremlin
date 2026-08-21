package me.lawsonhart.kremlin.info;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * The commands that only print something: /help, /rules, /discord and friends.
 *
 * All of them are the same command with a different key, so they are one class and one config
 * map rather than a file each. What any of them says is entirely config -- point them all at the
 * same lines if that is what you want, or give each its own.
 *
 * Deliberately a fixed set rather than "any key becomes a command": a command has to be declared
 * in plugin.yml to exist at all, so a config-only entry would silently do nothing.
 */
public final class InfoCommands implements CommandExecutor {

    /** Every label this answers to. Each needs a matching entry in plugin.yml. */
    public static final List<String> LABELS =
            List.of("help", "rules", "discord", "website", "vote", "store", "links");

    private final Combat plugin;

    public InfoCommands(final Combat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String key = Combat.rootCommand(label);
        if (!Perms.may(sender, "kremlin." + key, "essentials." + key)) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }

        final List<Component> lines = plugin.messages().lines("info." + key);
        if (lines.isEmpty()) {
            // Said plainly rather than silently: an unconfigured command that prints nothing
            // reads as a broken plugin, and the person who can fix it is the one who sees this.
            sender.sendMessage(plugin.msg("info-unset", Placeholder.unparsed("key", "info." + key)));
            return true;
        }
        lines.forEach(sender::sendMessage);
        return true;
    }
}
