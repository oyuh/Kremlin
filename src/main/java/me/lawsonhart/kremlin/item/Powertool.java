package me.lawsonhart.kremlin.item;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import me.lawsonhart.kremlin.core.Users;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * /powertool: bind a command to the item type in your hand, then run it by clicking with that
 * item. /powertooltoggle turns all of your bindings off without losing them.
 *
 * Bound per material rather than per stack, which is what makes it survive breaking the tool and
 * picking up another. Stored per player in users.yml, so a binding outlives a restart the way
 * Essentials' did.
 */
public final class Powertool implements Listener, CommandExecutor, TabCompleter {

    static final String KEY = "powertool";
    static final String ENABLED = "powertool-enabled";

    private static final Set<String> TOGGLE = Set.of("powertooltoggle", "ptt", "epowertooltoggle");

    private final Combat plugin;
    private final Users users;

    public Powertool(final Combat plugin, final Users users) {
        this.plugin = plugin;
        this.users = users;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- there is nothing in the console's hand."));
            return true;
        }
        if (!Perms.may(p, "kremlin.powertool", "essentials.powertool")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (TOGGLE.contains(Combat.rootCommand(label))) {
            final boolean now = !enabled(p);
            users.set(p.getUniqueId(), ENABLED, now);
            p.sendMessage(plugin.msg(now ? "powertool-enabled" : "powertool-disabled"));
            return true;
        }

        final ItemStack held = p.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            p.sendMessage(plugin.msg("hold-something"));
            return true;
        }
        final String material = held.getType().getKey().getKey();

        if (args.length == 0 || args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("none")) {
            users.set(p.getUniqueId(), KEY + "." + material, null);
            p.sendMessage(plugin.msg("powertool-cleared", Placeholder.unparsed("item", material)));
            return true;
        }
        // Leading slash optional, so both /powertool /home and /powertool home read the same.
        final String line = String.join(" ", args);
        users.set(p.getUniqueId(), KEY + "." + material,
                line.startsWith("/") ? line.substring(1) : line);
        p.sendMessage(plugin.msg("powertool-bound", Placeholder.unparsed("item", material),
                Placeholder.unparsed("command", line)));
        return true;
    }

    private boolean enabled(final Player p) {
        return users.flag(p.getUniqueId(), ENABLED, true);
    }

    /**
     * HIGHEST and ignoreCancelled, so a powertool never fires on a click something else already
     * refused -- clicking a protected block should not run the bound command anyway.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        // The event fires once per hand; without this the command would run twice per click.
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final ItemStack held = event.getItem();
        if (held == null || held.isEmpty()) return;

        final Player p = event.getPlayer();
        if (!enabled(p) || !Perms.may(p, "kremlin.powertool", "essentials.powertool")) return;
        final String bound = users.text(p.getUniqueId(), KEY + "." + held.getType().getKey().getKey());
        if (bound == null || bound.isBlank()) return;

        event.setCancelled(true);
        p.performCommand(bound);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        if (args.length != 1 || TOGGLE.contains(Combat.rootCommand(label))) return List.of();
        return Names.filter(args[0], List.of("clear"));
    }

    /** What this player has bound, for the options menu and /powertool with no argument. */
    public List<String> bindings(final Player p) {
        final Map<String, String> bound = users.map(p.getUniqueId(), KEY);
        final List<String> out = new ArrayList<>();
        for (final Map.Entry<String, String> e : bound.entrySet()) {
            out.add(e.getKey().toLowerCase(Locale.ROOT) + " -> /" + e.getValue());
        }
        return out;
    }

    /** Only used to keep the material name valid when a binding is read back. */
    static Material material(final String key) {
        return Material.matchMaterial(key);
    }
}
