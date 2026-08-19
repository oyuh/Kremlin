package me.lawsonhart.kremlin;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
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

/**
 * The combat action bar's style picker.
 *
 * This used to live inside /subclaim, which was a leftover: subclaims belong to SimpleTeams, and
 * a chest-locking menu was never the place to choose how much your combat timer shows. The
 * picker is the only part that was ever ours, so it moved out on its own as /kremlin bar.
 */
public final class BarMenu implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int STYLE_SLOT = 13;

    private final Combat plugin;

    BarMenu(Combat plugin) {
        this.plugin = plugin;
    }

    /** Marker holder so clicks in our menu are identified by identity, not by title text. */
    private static final class Menu implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void openGui(Player p) {
        Menu holder = new Menu();
        Inventory inv = plugin.getServer().createInventory(holder, 27, plugin.msg("gui-title"));
        holder.inv = inv;
        inv.setItem(STYLE_SLOT, styleItem(p));
        p.openInventory(inv);
    }

    private ItemStack styleItem(Player p) {
        Combat.BarStyle current = plugin.styleOf(p);
        List<String> lore = new ArrayList<>();
        for (Combat.BarStyle s : Combat.BarStyle.values()) {
            lore.add((s == current ? "<green>> " : "<dark_gray>  ") + s.label + " <dark_gray>- " + s.example);
        }
        lore.add("");
        lore.add("<yellow>Click to change");
        return item(Material.COMPARATOR, "<gold>Action bar: <white>" + current.label, lore);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(l -> MM.deserialize(l).decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return stack;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (e.getRawSlot() != STYLE_SLOT || !(e.getWhoClicked() instanceof Player p)) return;
        plugin.cycleStyle(p);
        e.getInventory().setItem(STYLE_SLOT, styleItem(p));
    }

    /** Clicks alone don't stop a drag depositing items into the menu. */
    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
