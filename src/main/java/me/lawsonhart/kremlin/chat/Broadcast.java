package me.lawsonhart.kremlin.chat;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Format;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * /broadcast. The wrapper around the text lives in messages.yml so the prefix is configurable
 * without touching the command; MiniMessage and legacy codes both work in the message itself.
 */
public final class Broadcast implements CommandExecutor {

    private final Combat plugin;

    public Broadcast(final Combat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!Perms.may(sender, "kremlin.broadcast", "essentials.broadcast")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.msg("broadcast-usage"));
            return true;
        }
        // Staff-typed, so their codes are honoured in full -- this is not player chat.
        plugin.getServer().broadcast(plugin.msg("broadcast", Placeholder.component("message",
                Format.body(String.join(" ", args), new Format.Allowed(true, true, true)))));
        return true;
    }
}
