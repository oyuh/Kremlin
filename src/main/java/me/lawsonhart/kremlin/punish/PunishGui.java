package me.lawsonhart.kremlin.punish;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /punish &lt;player&gt;: the configured ladder, one click per rung.
 *
 * Every rung is just a console command from the config with {@code {player}} filled in, so this
 * is a launcher rather than a second punishment engine. Adding a rung is a config edit, and a
 * rung can run anything -- including another plugin's command -- without a line of code here.
 */
public final class PunishGui implements Listener, CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int SIZE = 27;
    private static final int FIRST = 10;

    /** One rung: what it looks like, and what it runs. */
    private record Rung(String name, Material material, String command, List<String> lore) {}

    private final Combat plugin;
    private List<Rung> ladder = List.of();

    public PunishGui(final Combat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        final List<Rung> rungs = new ArrayList<>();
        final List<?> raw = plugin.getConfig().getList("punish.ladder", List.of());
        for (final Object entry : raw) {
            if (!(entry instanceof java.util.Map<?, ?> map)) continue;
            final String command = Punishments.str(map, "command", "");
            if (command.isBlank()) continue;
            Material material = Material.matchMaterial(Punishments.str(map, "material", "PAPER"));
            if (material == null) material = Material.PAPER;
            rungs.add(new Rung(Punishments.str(map, "name", command), material, command,
                    List.of("<dark_gray>/" + command)));
        }
        ladder = List.copyOf(rungs);
    }

    /** Marker holder, carrying who the menu is about. */
    private static final class Menu implements InventoryHolder {
        private Inventory inv;
        private UUID target;
        private String targetName;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- this one is a menu."));
            return true;
        }
        if (!Perms.may(p, "kremlin.punish", "essentials.punish")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(plugin.msg("punish-usage", Placeholder.unparsed("command", "punish")));
            return true;
        }
        if (ladder.isEmpty()) {
            p.sendMessage(plugin.msg("punish-no-ladder"));
            return true;
        }

        final UUID id = plugin.findPlayer(args[0]);
        if (id == null) {
            p.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
            return true;
        }
        final OfflinePlayer target = plugin.getServer().getOfflinePlayer(id);
        final Player online = target.getPlayer();
        if (online != null && plugin.getPunishments().exempt(online)) {
            p.sendMessage(plugin.msg("exempt", Placeholder.unparsed("player", args[0])));
            return true;
        }

        final Menu holder = new Menu();
        holder.target = id;
        holder.targetName = target.getName() == null ? args[0] : target.getName();
        final Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                plugin.msg("punish-title", Placeholder.unparsed("player", holder.targetName)));
        holder.inv = inv;
        for (int i = 0; i < ladder.size() && FIRST + i < SIZE; i++) {
            final Rung rung = ladder.get(i);
            inv.setItem(FIRST + i, item(rung.material(), "<red>" + rung.name(), rung.lore()));
        }
        p.openInventory(inv);
        return true;
    }

    private static ItemStack item(final Material material, final String name, final List<String> lore) {
        final ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(l -> MM.deserialize(l).decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return stack;
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        final int index = event.getRawSlot() - FIRST;
        if (index < 0 || index >= ladder.size()) return;
        // Re-check on click, not only on open: the menu may have been sitting there a while.
        if (!Perms.may(p, "kremlin.punish", "essentials.punish")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return;
        }

        final String line = ladder.get(index).command().replace("{player}", menu.targetName);
        p.closeInventory();
        // As console, so a rung can reach commands the staff member holding the menu cannot --
        // which is the point of a ladder that any moderator can use.
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), line);
        p.sendMessage(plugin.msg("punish-ran", Placeholder.unparsed("command", line)));
    }

    /** Clicks alone don't stop a drag depositing items into the menu. */
    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        return args.length == 1
                ? Names.filter(args[0], Names.online(plugin.getServer(), Names.offlineNames(plugin.getServer()))) : List.of();
    }

    public String describe() {
        return ladder.size() + " punish rung(s)";
    }
}
