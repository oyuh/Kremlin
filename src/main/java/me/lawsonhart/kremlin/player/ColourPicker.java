package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.core.Items;
import me.lawsonhart.kremlin.core.Palette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * The colour grid for one gradient stop. Like the menu it holds its own state, so which stop is
 * being edited is answered by the open inventory itself.
 */
public final class ColourPicker implements InventoryHolder {

    static final int SLOT_CUSTOM = 52;
    static final int SLOT_BACK = 53;

    private final NickMenu menu;
    private final int index;
    private final Inventory inventory;

    /** index == menu.stopCount() means the chosen colour is appended as a new stop. */
    ColourPicker(final NickMenu menu, final int index) {
        this.menu = menu;
        this.index = index;
        final boolean adding = index >= menu.stopCount();
        this.inventory = Bukkit.createInventory(this, 54, Component.text(
                adding ? "Add a colour" : "Colour " + (index + 1),
                NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
        render();
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    NickMenu menu() {
        return this.menu;
    }

    int index() {
        return this.index;
    }

    private void render() {
        for (int slot = 0; slot < Palette.COLOURS.size(); slot++) {
            final TextColor colour = Palette.COLOURS.get(slot);
            this.inventory.setItem(slot, Items.dyed(colour,
                    Component.text(colour.asHexString(), colour),
                    Component.text("Click to use this colour", NamedTextColor.DARK_GRAY)));
        }
        this.inventory.setItem(SLOT_CUSTOM, Items.of(Material.NAME_TAG,
                Component.text("Custom hex", NamedTextColor.YELLOW),
                Component.text("Type any hex code in chat, e.g. #ff8800", NamedTextColor.DARK_GRAY)));
        this.inventory.setItem(SLOT_BACK, Items.of(Material.ARROW,
                Component.text("Back", NamedTextColor.GRAY),
                Component.text("Return without changing this colour", NamedTextColor.DARK_GRAY)));
    }
}
