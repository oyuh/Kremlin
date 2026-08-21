package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.core.Palette;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/** /nickg: the menu, the colour picker, the chat prompts, and the EssentialsX hand-off. */
public final class NickGui implements Listener, CommandExecutor {

    /** EssentialsX validates nicknames against ^[a-zA-Z_0-9<section>]+$, so keep the text plain. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");

    private enum Ask { NAME, HEX }

    /** A menu waiting on one line of chat. index is the gradient stop, unused for NAME. */
    private record Prompt(NickMenu menu, Ask ask, int index) {
    }

    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final Nicknames nicknames;

    public NickGui(final Plugin plugin, final Nicknames nicknames) {
        this.plugin = plugin;
        this.nicknames = nicknames;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players have nicknames.", NamedTextColor.RED));
            return true;
        }
        this.prompts.remove(player.getUniqueId());
        player.openInventory(new NickMenu(player).getInventory());
        return true;
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        final InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof NickMenu) && !(holder instanceof ColourPicker)) {
            return;
        }
        // Cancel every click while either screen is open, including shift-clicks from the player's
        // own inventory, so nothing can be dropped in.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }

        if (holder instanceof NickMenu menu) {
            clickMenu(player, menu, event.getRawSlot(), event.isRightClick());
        } else {
            clickPicker(player, (ColourPicker) holder, event.getRawSlot());
        }
    }

    private void clickMenu(final Player player, final NickMenu menu, final int slot, final boolean rightClick) {
        final int stop = NickMenu.STOP_SLOTS.indexOf(slot);
        if (stop >= 0) {
            if (rightClick && stop < menu.stopCount()) {
                menu.removeStop(stop);
            } else if (stop <= menu.stopCount() && stop < NickMenu.MAX_STOPS) {
                // Clicking a stop, or the Add button just past the last one, opens the picker.
                player.openInventory(new ColourPicker(menu, stop).getInventory());
            }
            return;
        }

        switch (slot) {
            case NickMenu.SLOT_NAME -> prompt(player, new Prompt(menu, Ask.NAME, 0),
                    "Type your nickname in chat - letters, digits and underscores, up to 16.");
            case NickMenu.SLOT_APPLY -> {
                player.closeInventory();
                player.sendMessage(Component.text("Applying ", NamedTextColor.GRAY).append(menu.nick().preview()));
                // Straight into our own store as MiniMessage. It used to be serialised down to
                // legacy codes and handed to Essentials' /nick, which flattened the gradient.
                this.nicknames.set(player.getUniqueId(), menu.nick().miniMessage());
            }
            case NickMenu.SLOT_RESET -> {
                player.closeInventory();
                this.nicknames.set(player.getUniqueId(), null);
                player.sendMessage(Component.text("Nickname cleared.", NamedTextColor.GRAY));
            }
            default -> {
                final int decoration = slot - NickMenu.SLOT_DECORATION;
                if (decoration >= 0 && decoration < Nick.DECORATIONS.size()) {
                    menu.toggle(Nick.DECORATIONS.get(decoration));
                }
            }
        }
    }

    private void clickPicker(final Player player, final ColourPicker picker, final int slot) {
        if (slot >= 0 && slot < Palette.COLOURS.size()) {
            picker.menu().stop(picker.index(), Palette.COLOURS.get(slot));
            player.openInventory(picker.menu().getInventory());
        } else if (slot == ColourPicker.SLOT_CUSTOM) {
            prompt(player, new Prompt(picker.menu(), Ask.HEX, picker.index()),
                    "Type a hex colour in chat, like #ff8800.");
        } else if (slot == ColourPicker.SLOT_BACK) {
            player.openInventory(picker.menu().getInventory());
        }
    }

    @EventHandler
    public void onDrag(final InventoryDragEvent event) {
        final InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof NickMenu || holder instanceof ColourPicker) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final Prompt prompt = this.prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);

        final String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        // Chat arrives off the region thread, so hop onto the player's scheduler before touching
        // the menu or reopening the inventory.
        player.getScheduler().run(this.plugin, task -> accept(player, prompt, input), null);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.prompts.remove(event.getPlayer().getUniqueId());
    }

    private void prompt(final Player player, final Prompt prompt, final String message) {
        this.prompts.put(player.getUniqueId(), prompt);
        player.closeInventory();
        player.sendMessage(Component.text(message + " Type 'cancel' to go back.", NamedTextColor.AQUA));
    }

    private void accept(final Player player, final Prompt prompt, final String input) {
        if (!input.equalsIgnoreCase("cancel")) {
            switch (prompt.ask()) {
                case NAME -> {
                    if (!NAME.matcher(input).matches()) {
                        retry(player, prompt, "Letters, digits and underscores only, up to 16 of them.");
                        return;
                    }
                    prompt.menu().name(input);
                }
                case HEX -> {
                    if (!HEX.matcher(input).matches()) {
                        retry(player, prompt, "That is not a hex colour.");
                        return;
                    }
                    final String hex = input.startsWith("#") ? input : "#" + input;
                    prompt.menu().stop(prompt.index(), TextColor.fromHexString(hex));
                }
            }
        }
        player.openInventory(prompt.menu().getInventory());
    }

    private void retry(final Player player, final Prompt prompt, final String why) {
        this.prompts.put(player.getUniqueId(), prompt);
        player.sendMessage(Component.text(why + " Try again, or type 'cancel'.", NamedTextColor.RED));
    }

}
