package me.lawsonhart.kremlin.options;

import me.lawsonhart.kremlin.chat.Msg;
import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Users;
import me.lawsonhart.kremlin.teleport.Tpa;
import me.lawsonhart.kremlin.teleport.TpCommands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
 * /options: the per-player toggles, in one place.
 *
 * This absorbed the action-bar style picker, which was /kremlin bar and before that lived inside
 * /subclaim. There was never a reason for "how much does my combat timer show" to be a menu of
 * its own, and there is even less of one now that private messages and teleports have toggles
 * that belong beside it.
 */
public final class OptionsGui implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final int SIZE = 27;
    private static final int SLOT_BAR = 10;
    private static final int SLOT_MSG = 11;
    private static final int SLOT_MSG_SOUND = 12;
    private static final int SLOT_TP = 13;
    private static final int SLOT_TPA_SOUND = 14;
    private static final int SLOT_TPA_AUTO = 15;
    private static final int SLOT_IGNORE = 16;

    private final Combat plugin;
    private final Users users;

    public OptionsGui(final Combat plugin, final Users users) {
        this.plugin = plugin;
        this.users = users;
    }

    /** Marker holder so clicks are identified by identity, not by the title text. */
    private static final class Menu implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- these are your own settings."));
            return true;
        }
        open(p);
        return true;
    }

    public void open(final Player p) {
        final Menu holder = new Menu();
        final Inventory inv = plugin.getServer().createInventory(holder, SIZE, plugin.msg("options-title"));
        holder.inv = inv;
        draw(p, inv);
        p.openInventory(inv);
    }

    private void draw(final Player p, final Inventory inv) {
        inv.setItem(SLOT_BAR, barItem(p));
        inv.setItem(SLOT_MSG, toggleItem(Material.PAPER, "Private messages",
                flag(p, Msg.TOGGLE), "Whether other players can /msg you."));
        inv.setItem(SLOT_MSG_SOUND, toggleItem(Material.EXPERIENCE_BOTTLE, "Message sound",
                flag(p, Msg.SOUND), "An xp-orb ping when somebody messages you."));
        inv.setItem(SLOT_TP, toggleItem(Material.ENDER_PEARL, "Teleport requests",
                flag(p, TpCommands.TOGGLE), "Whether /tpa and /tp can bring you anywhere."));
        inv.setItem(SLOT_TPA_SOUND, toggleItem(Material.NOTE_BLOCK, "Teleport sound",
                flag(p, Tpa.SOUND), "A ping when somebody asks to teleport to you."));
        inv.setItem(SLOT_TPA_AUTO, toggleItem(Material.LIME_DYE, "Auto-accept teleports",
                flag(p, Tpa.AUTO), "Let anyone teleport to you without asking."));
        inv.setItem(SLOT_IGNORE, ignoreItem(p));
    }

    /**
     * Every toggle here reads true unless it is auto-accept, which has to be opted into --
     * a default of "anyone may teleport to me" is not a default anybody chose.
     */
    private boolean flag(final Player p, final String key) {
        return users.flag(p.getUniqueId(), key, !Tpa.AUTO.equals(key));
    }

    private ItemStack barItem(final Player p) {
        final Combat.BarStyle current = plugin.styleOf(p);
        final List<String> lore = new ArrayList<>();
        for (final Combat.BarStyle s : Combat.BarStyle.values()) {
            lore.add((s == current ? "<green>> " : "<dark_gray>  ") + s.label + " <dark_gray>- " + s.example);
        }
        lore.add("");
        lore.add("<yellow>Click to change");
        return item(Material.COMPARATOR, "<gold>Combat bar: <white>" + current.label, lore);
    }

    private static ItemStack toggleItem(final Material material, final String name,
                                        final boolean on, final String what) {
        return item(material, (on ? "<green>" : "<red>") + name + ": <white>" + (on ? "on" : "off"),
                List.of("<gray>" + what, "", "<yellow>Click to " + (on ? "turn off" : "turn on")));
    }

    /**
     * Read-only. Removing somebody from here would mean paging a list that is usually empty and
     * occasionally enormous; /ignore already toggles by name, and this only has to answer "who
     * did I ignore, again".
     */
    private ItemStack ignoreItem(final Player p) {
        final List<String> ignored = users.list(p.getUniqueId(), "ignored");
        final List<String> lore = new ArrayList<>();
        if (ignored.isEmpty()) {
            lore.add("<dark_gray>Nobody.");
        } else {
            for (final String raw : ignored) {
                final OfflinePlayer other = plugin.getServer().getOfflinePlayer(UUID.fromString(raw));
                lore.add("<gray>- <white>" + (other.getName() == null ? raw : other.getName()));
            }
        }
        lore.add("");
        lore.add("<dark_gray>Use /ignore <player> to change this.");
        return item(Material.BARRIER, "<gold>Ignoring: <white>" + ignored.size(), lore);
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
        if (!(event.getInventory().getHolder() instanceof Menu)) return;
        // Cancel every click, including shift-clicks out of the player's own inventory, so
        // nothing can be moved into a menu that is not a container.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }

        switch (event.getRawSlot()) {
            case SLOT_BAR -> plugin.cycleStyle(p);
            case SLOT_MSG -> flip(p, Msg.TOGGLE);
            case SLOT_MSG_SOUND -> flip(p, Msg.SOUND);
            case SLOT_TP -> flip(p, TpCommands.TOGGLE);
            case SLOT_TPA_SOUND -> flip(p, Tpa.SOUND);
            case SLOT_TPA_AUTO -> flip(p, Tpa.AUTO);
            default -> {
                return; // the ignore item and the empty slots do nothing
            }
        }
        draw(p, event.getInventory());
    }

    private void flip(final Player p, final String key) {
        users.set(p.getUniqueId(), key, !flag(p, key));
    }

    /** Clicks alone don't stop a drag depositing items into the menu. */
    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
}
