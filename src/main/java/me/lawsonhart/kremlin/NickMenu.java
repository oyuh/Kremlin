package me.lawsonhart.kremlin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * One player's in-progress nickname. It is the inventory's holder, so the draft state lives and
 * dies with the open menu - no tracking map, nothing to clean up.
 */
final class NickMenu implements InventoryHolder {

    static final int MIN_STOPS = 2;
    static final int MAX_STOPS = 10;

    static final int SLOT_PREVIEW = 4;
    static final int SLOT_NAME = 10;
    static final int SLOT_DECORATION = 37;
    static final int SLOT_RESET = 46;
    static final int SLOT_APPLY = 52;

    /** Two rows of five: the gradient's stops, left to right, top row first. */
    static final List<Integer> STOP_SLOTS = List.of(20, 21, 22, 23, 24, 29, 30, 31, 32, 33);

    private final Inventory inventory;
    private final EnumSet<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class);
    private final List<TextColor> stops = new ArrayList<>(
            List.of(TextColor.color(0x55FFFF), TextColor.color(0xFF55FF)));
    private String name;

    NickMenu(final Player player) {
        this.name = player.getName();
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text("Nickname Builder", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        render();
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    Nick nick() {
        return Nick.gradient(this.name, List.copyOf(this.stops), Set.copyOf(this.decorations));
    }

    int stopCount() {
        return this.stops.size();
    }

    void name(final String name) {
        this.name = name;
        render();
    }

    /** Replaces the stop at that index, or appends when the index is past the end. */
    void stop(final int index, final TextColor colour) {
        if (index < this.stops.size()) {
            this.stops.set(index, colour);
        } else if (this.stops.size() < MAX_STOPS) {
            this.stops.add(colour);
        }
        render();
    }

    void removeStop(final int index) {
        if (this.stops.size() > MIN_STOPS && index < this.stops.size()) {
            this.stops.remove(index);
            render();
        }
    }

    void toggle(final TextDecoration decoration) {
        if (!this.decorations.add(decoration)) {
            this.decorations.remove(decoration);
        }
        render();
    }

    private void render() {
        final Nick nick = nick();
        final Component preview = nick.preview();
        this.inventory.clear();
        this.inventory.setItem(SLOT_PREVIEW, Items.of(Material.PAPER, preview,
                Component.text("This is how your nickname will look.", NamedTextColor.GRAY),
                Component.text(nick.miniMessage(), NamedTextColor.DARK_GRAY)));
        this.inventory.setItem(SLOT_NAME, Items.of(Material.NAME_TAG,
                Component.text("Set name text", NamedTextColor.YELLOW),
                Component.text("Currently: ", NamedTextColor.GRAY).append(preview),
                Component.text("Click to type it in chat", NamedTextColor.DARK_GRAY)));

        renderStops();

        for (int i = 0; i < Nick.DECORATIONS.size(); i++) {
            final TextDecoration decoration = Nick.DECORATIONS.get(i);
            final boolean on = this.decorations.contains(decoration);
            final String label = decoration.name().charAt(0)
                    + decoration.name().substring(1).toLowerCase(Locale.ROOT);
            // Style the label only while the toggle is on, so the button shows its own state.
            // Decorating it either way made every toggle look permanently switched on.
            final Component name = Component.text(label, on ? NamedTextColor.GREEN : NamedTextColor.GRAY);
            this.inventory.setItem(SLOT_DECORATION + i, Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    on ? name.decorate(decoration) : name,
                    Component.text(on ? "ON - click to turn off" : "OFF - click to turn on", NamedTextColor.DARK_GRAY)));
        }

        this.inventory.setItem(SLOT_RESET, Items.of(Material.BARRIER,
                Component.text("Remove nickname", NamedTextColor.RED),
                Component.text("Runs /nick off", NamedTextColor.DARK_GRAY)));
        this.inventory.setItem(SLOT_APPLY, Items.of(Material.EMERALD,
                Component.text("Apply nickname", NamedTextColor.GREEN),
                Component.text("Hands the finished nickname to EssentialsX", NamedTextColor.DARK_GRAY)));
    }

    /** Every stop, then a single Add button, then nothing - so the row reads as a queue. */
    private void renderStops() {
        for (int i = 0; i < STOP_SLOTS.size(); i++) {
            final int slot = STOP_SLOTS.get(i);
            if (i < this.stops.size()) {
                final TextColor colour = this.stops.get(i);
                this.inventory.setItem(slot, Items.dyed(colour,
                        Component.text("Colour " + (i + 1) + ": " + colour.asHexString(), colour),
                        Component.text("Click to pick a different colour", NamedTextColor.DARK_GRAY),
                        Component.text(this.stops.size() > MIN_STOPS
                                ? "Right-click to remove it"
                                : "Two colours is the minimum", NamedTextColor.DARK_GRAY)));
            } else if (i == this.stops.size()) {
                this.inventory.setItem(slot, Items.of(Material.LIME_DYE,
                        Component.text("Add a colour", NamedTextColor.GREEN),
                        Component.text("Up to " + MAX_STOPS + " colours in the gradient", NamedTextColor.DARK_GRAY)));
            }
        }
    }
}
