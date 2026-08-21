package me.lawsonhart.kremlin.core;

import java.util.Arrays;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/** Menu icons. Leather armour is the only item that can be tinted an arbitrary hex colour. */
public final class Items {

    private Items() {
    }

    public static ItemStack of(final Material material, final Component name, final Component... lore) {
        final ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(plainItalics(name));
            meta.lore(Arrays.stream(lore).map(Items::plainItalics).toList());
        });
        return stack;
    }

    /**
     * Item text renders italic unless told otherwise, so we turn it off - but only when the
     * caller didn't ask for italics. Setting it unconditionally is what stopped the menu's own
     * Italic toggle from ever looking italic.
     */
    private static Component plainItalics(final Component text) {
        return text.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET
                ? text.decoration(TextDecoration.ITALIC, false)
                : text;
    }

    public static ItemStack dyed(final TextColor colour, final Component name, final Component... lore) {
        final ItemStack stack = of(Material.LEATHER_CHESTPLATE, name, lore);
        stack.editMeta(LeatherArmorMeta.class, meta -> meta.setColor(Color.fromRGB(colour.value())));
        return stack;
    }
}
