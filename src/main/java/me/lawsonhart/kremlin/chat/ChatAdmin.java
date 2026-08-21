package me.lawsonhart.kremlin.chat;

import me.lawsonhart.kremlin.combat.Combat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /clearchat and /slowchat.
 *
 * Both are ordinary registered commands rather than the PlayerCommandPreprocessEvent capture the
 * teleport features use: Essentials owns no label here, so there is nothing to take over, and
 * registering properly is what gets these working from console and in tab-completion.
 *
 * The throttle cancels at LOW priority, before Essentials formats and broadcasts the message, so
 * a rate-limited line never reaches chat at all. Homes' rename prompt sits at LOWEST and is
 * already cancelled by then, so renaming a home is never throttled.
 */
public final class ChatAdmin implements Listener, CommandExecutor, TabCompleter {

    /** Enough blank lines to push anything off the tallest chat setting. */
    private static final int BLANK_LINES = 120;

    private final Combat combat;
    /** Milliseconds between messages, 0 when slow chat is off. */
    private volatile long slowMillis;
    /** uuid -> when they last spoke, for the throttle */
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public ChatAdmin(Combat combat) {
        this.combat = combat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("clearchat")) {
            return clearChat(sender);
        }
        return slowChat(sender, args);
    }

    private boolean clearChat(CommandSender sender) {
        if (!may(sender, "kremlin.clearchat", "essentials.clearchat")) {
            sender.sendMessage(combat.msg("chat-no-permission"));
            return true;
        }
        Component blank = Component.empty();
        for (Player p : combat.getServer().getOnlinePlayers()) {
            // Staff keep their scrollback, so whatever was being cleared can still be read.
            if (p.hasPermission("kremlin.clearchat.exempt")) continue;
            for (int i = 0; i < BLANK_LINES; i++) p.sendMessage(blank);
        }
        combat.getServer().broadcast(combat.msg("chat-cleared",
                Placeholder.component("player", displayName(sender))));
        return true;
    }

    private boolean slowChat(CommandSender sender, String[] args) {
        if (!may(sender, "kremlin.slowchat", "essentials.slowchat")) {
            sender.sendMessage(combat.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(combat.msg(this.slowMillis > 0 ? "slowchat-status-on" : "slowchat-status-off",
                    Placeholder.unparsed("time", String.valueOf(this.slowMillis / 1000))));
            return true;
        }

        long seconds = parseSeconds(args[0]);
        if (seconds < 0) {
            sender.sendMessage(combat.msg("slowchat-usage"));
            return true;
        }
        this.slowMillis = seconds * 1000L;
        this.lastMessage.clear();
        combat.getServer().broadcast(combat.msg(seconds > 0 ? "slowchat-on" : "slowchat-off",
                Placeholder.unparsed("time", String.valueOf(seconds)),
                Placeholder.component("player", displayName(sender))));
        return true;
    }

    /** Seconds, "off"/"0" for off, or -1 when it isn't a number we can use. */
    static long parseSeconds(String argument) {
        if (argument.equalsIgnoreCase("off") || argument.equalsIgnoreCase("none")) return 0L;
        try {
            long seconds = Long.parseLong(argument);
            // A day of slow chat is somebody fat-fingering it, not a plan.
            return seconds < 0 || seconds > 3600 ? -1L : seconds;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        long window = this.slowMillis;
        if (window <= 0) return;
        Player p = e.getPlayer();
        if (p.hasPermission("kremlin.slowchat.bypass")) return;

        long now = System.currentTimeMillis();
        Long last = this.lastMessage.get(p.getUniqueId());
        if (last != null && now - last < window) {
            e.setCancelled(true);
            p.sendMessage(combat.msg("slowchat-wait",
                    Placeholder.unparsed("time", Combat.secs(window - (now - last)))));
            return;
        }
        this.lastMessage.put(p.getUniqueId(), now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.lastMessage.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !command.getName().equalsIgnoreCase("slowchat")) return List.of();
        return List.of("off", "3", "5", "10", "30").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static boolean may(CommandSender sender, String... nodes) {
        for (String node : nodes) {
            if (sender.hasPermission(node)) return true;
        }
        return false;
    }

    /** Console has no display name, and a player's carries their nickname formatting. */
    private static Component displayName(CommandSender sender) {
        return sender instanceof Player p ? p.displayName() : Component.text(sender.getName());
    }

    String describe() {
        return this.slowMillis > 0 ? "slow chat " + this.slowMillis / 1000 + "s" : "slow chat off";
    }
}
