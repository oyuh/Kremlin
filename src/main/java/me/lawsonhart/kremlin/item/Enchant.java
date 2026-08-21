package me.lawsonhart.kremlin.item;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /enchant, with unsafe levels allowed.
 *
 * Enchantments are looked up in the registry by their modern key, which is already the friendly
 * name ({@code sharpness}, not {@code DAMAGE_ALL}). {@link #ALIASES} only has to cover what the
 * registry will not answer to: the old Bukkit constants, which is what an Essentials-era config
 * or a long-standing habit will produce, and the short forms people actually type.
 */
public final class Enchant implements CommandExecutor, TabCompleter {

    /** Old Bukkit names and common short forms -> the modern registry key. */
    static final Map<String, String> ALIASES = Map.ofEntries(
            // Old Bukkit constants.
            Map.entry("damage_all", "sharpness"),
            Map.entry("damage_undead", "smite"),
            Map.entry("damage_arthropods", "bane_of_arthropods"),
            Map.entry("protection_environmental", "protection"),
            Map.entry("protection_fire", "fire_protection"),
            Map.entry("protection_explosions", "blast_protection"),
            Map.entry("protection_projectile", "projectile_protection"),
            Map.entry("protection_fall", "feather_falling"),
            Map.entry("oxygen", "respiration"),
            Map.entry("water_worker", "aqua_affinity"),
            Map.entry("dig_speed", "efficiency"),
            Map.entry("durability", "unbreaking"),
            Map.entry("loot_bonus_blocks", "fortune"),
            Map.entry("loot_bonus_mobs", "looting"),
            Map.entry("arrow_damage", "power"),
            Map.entry("arrow_knockback", "punch"),
            Map.entry("arrow_fire", "flame"),
            Map.entry("arrow_infinite", "infinity"),
            Map.entry("luck", "luck_of_the_sea"),
            Map.entry("sweeping", "sweeping_edge"),
            // Short forms.
            Map.entry("sharp", "sharpness"),
            Map.entry("prot", "protection"),
            Map.entry("eff", "efficiency"),
            Map.entry("unb", "unbreaking"),
            Map.entry("fort", "fortune"),
            Map.entry("silk", "silk_touch"),
            Map.entry("dura", "unbreaking"),
            Map.entry("knock", "knockback"),
            Map.entry("fireaspect", "fire_aspect"));

    private final Combat plugin;
    private boolean unsafeAllowed = true;

    public Enchant(final Combat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        unsafeAllowed = plugin.getConfig().getBoolean("unsafe-enchantments", true);
    }

    /**
     * The enchantment somebody meant, or null. Case and the {@code minecraft:} namespace are both
     * accepted, as are spaces where the key uses underscores.
     */
    static Enchantment resolve(final String typed) {
        if (typed == null || typed.isBlank()) return null;
        String name = typed.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        name = ALIASES.getOrDefault(name, name);
        final NamespacedKey key = NamespacedKey.fromString(name, null);
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    /**
     * The level to actually apply.
     *
     * Past the enchantment's own maximum is "unsafe" -- the game will render it and mostly behave,
     * but it is exactly the thing that corrupts clients when overdone, so it is behind a
     * permission. Without that permission the level is clamped rather than refused, which is
     * friendlier than an error for someone who typed 10 meaning "lots".
     */
    static int level(final Enchantment enchantment, final int asked, final boolean unsafe) {
        if (unsafe) return Math.max(1, Math.min(asked, Short.MAX_VALUE));
        return Math.max(enchantment.getStartLevel(), Math.min(asked, enchantment.getMaxLevel()));
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- you have to be holding something."));
            return true;
        }
        if (!Perms.may(p, "kremlin.enchant", "essentials.enchant")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(plugin.msg("enchant-usage"));
            return true;
        }

        final ItemStack held = p.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            p.sendMessage(plugin.msg("hold-something"));
            return true;
        }

        final Enchantment enchantment = resolve(args[0]);
        if (enchantment == null) {
            p.sendMessage(plugin.msg("enchant-unknown", Placeholder.unparsed("name", args[0])));
            return true;
        }
        // Per-enchantment permissions, the way Essentials gates them.
        final String key = enchantment.getKey().getKey();
        if (!Perms.may(p, "kremlin.enchant." + key, "essentials.enchant." + key,
                "kremlin.enchant.*", "essentials.enchant.*")
                && !Perms.may(p, "kremlin.enchant", "essentials.enchant")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }

        int asked = 1;
        if (args.length > 1) {
            try {
                asked = Integer.parseInt(args[1]);
            } catch (final NumberFormatException ex) {
                p.sendMessage(plugin.msg("enchant-usage"));
                return true;
            }
        }
        if (asked <= 0) {
            held.removeEnchantment(enchantment);
            p.sendMessage(plugin.msg("enchant-removed", Placeholder.unparsed("name", key)));
            return true;
        }

        final boolean unsafe = unsafeAllowed
                && Perms.may(p, "kremlin.enchantments.allowunsafe", "essentials.enchantments.allowunsafe");
        final int level = level(enchantment, asked, unsafe);
        // addUnsafeEnchantment either way: the level is already decided, and the safe path has
        // been clamped to the enchantment's own maximum, so this never applies more than asked.
        held.addUnsafeEnchantment(enchantment, level);

        p.sendMessage(plugin.msg("enchant-applied", Placeholder.unparsed("name", key),
                Placeholder.unparsed("level", String.valueOf(level))));
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        if (args.length != 1) return List.of();
        final List<String> names = new ArrayList<>();
        for (final Enchantment e : Registry.ENCHANTMENT) names.add(e.getKey().getKey());
        return Names.filter(args[0], names);
    }
}
