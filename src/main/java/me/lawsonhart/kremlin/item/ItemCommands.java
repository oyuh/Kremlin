package me.lawsonhart.kremlin.item;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Format;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The commands that act on what somebody is holding, or on their inventory.
 *
 * Grouped for the same reason {@code PlayerCommands} is: each is a few lines around one API call,
 * and they share the "held item, or nothing to do" opening. The branch is on the label.
 */
public final class ItemCommands implements CommandExecutor, TabCompleter {

    private static final Set<String> REPAIR = Set.of("repair", "erepair", "fix");
    private static final Set<String> MORE = Set.of("more", "emore");
    private static final Set<String> GIVE = Set.of("give", "egive", "i", "item", "eitem");
    private static final Set<String> SKULL = Set.of("skull", "eskull");
    private static final Set<String> ITEMNAME = Set.of("itemname", "iname", "eitemname");
    private static final Set<String> ITEMLORE = Set.of("itemlore", "il", "eitemlore");
    private static final Set<String> CLEAR = Set.of("clearinventory", "ci", "clean", "clearinvent", "eclearinventory");
    private static final Set<String> TRASH = Set.of("trash", "disposal", "edisposal");

    /** All codes allowed: these are staff commands, not player chat. */
    private static final Format.Allowed STAFF = new Format.Allowed(true, true, true);

    private final Combat plugin;

    private boolean repairEnchanted = true;
    private int defaultStackSize = -1;
    private int oversizedStackSize = 128;
    private int maxLoreLines = 10;

    public ItemCommands(final Combat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        repairEnchanted = plugin.getConfig().getBoolean("repair-enchanted", true);
        defaultStackSize = plugin.getConfig().getInt("default-stack-size", -1);
        oversizedStackSize = plugin.getConfig().getInt("oversized-stacksize", 128);
        maxLoreLines = plugin.getConfig().getInt("max-itemlore-lines", 10);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        if (GIVE.contains(cmd)) return give(sender, args);
        if (CLEAR.contains(cmd)) return clear(sender, args);

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- this one needs your hands."));
            return true;
        }
        if (TRASH.contains(cmd)) return trash(p);
        if (REPAIR.contains(cmd)) return repair(p, args);
        if (SKULL.contains(cmd)) return skull(p, args);

        // Everything left edits the held item.
        final String node = MORE.contains(cmd) ? "more" : ITEMNAME.contains(cmd) ? "itemname" : "itemlore";
        if (!Perms.may(p, "kremlin." + node, "essentials." + node)) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final ItemStack held = p.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            p.sendMessage(plugin.msg("hold-something"));
            return true;
        }
        if (MORE.contains(cmd)) return more(p, held);
        if (ITEMNAME.contains(cmd)) return itemName(p, held, args);
        return itemLore(p, held, args);
    }

    // ---------------------------------------------------------------- held item

    private boolean repair(final Player p, final String[] args) {
        final boolean all = args.length > 0 && args[0].equalsIgnoreCase("all");
        final String node = all ? "repair.all" : "repair";
        if (!Perms.may(p, "kremlin." + node, "essentials." + node)) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }

        final PlayerInventory inv = p.getInventory();
        int fixed = 0;
        if (all) {
            for (final ItemStack stack : inv.getContents()) fixed += repairOne(p, stack) ? 1 : 0;
            for (final ItemStack stack : inv.getArmorContents()) fixed += repairOne(p, stack) ? 1 : 0;
        } else {
            final ItemStack held = inv.getItemInMainHand();
            if (held.isEmpty()) {
                p.sendMessage(plugin.msg("hold-something"));
                return true;
            }
            if (!repairOne(p, held)) {
                p.sendMessage(plugin.msg("repair-nothing"));
                return true;
            }
            fixed = 1;
        }
        p.sendMessage(fixed == 0 ? plugin.msg("repair-nothing")
                : plugin.msg("repaired", Placeholder.unparsed("count", String.valueOf(fixed))));
        return true;
    }

    /** True when this stack was actually damaged and has now been mended. */
    private boolean repairOne(final Player p, final ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getAmount() > 1) return false;
        // repair-enchanted: false makes mending an enchanted tool its own permission, so a rank
        // can be allowed /repair without it becoming free upkeep on their best gear.
        if (!stack.getEnchantments().isEmpty() && !repairEnchanted
                && !Perms.may(p, "kremlin.repair.enchanted", "essentials.repair.enchanted")) {
            return false;
        }
        if (!(stack.getItemMeta() instanceof Damageable damageable) || !damageable.hasDamage()) return false;
        stack.editMeta(Damageable.class, meta -> meta.setDamage(0));
        return true;
    }

    private boolean more(final Player p, final ItemStack held) {
        final int max = Perms.may(p, "kremlin.oversizedstacks", "essentials.oversizedstacks")
                ? oversizedStackSize : held.getMaxStackSize();
        if (held.getAmount() >= max) {
            p.sendMessage(plugin.msg("more-full"));
            return true;
        }
        held.setAmount(max);
        p.sendMessage(plugin.msg("more", Placeholder.unparsed("count", String.valueOf(max))));
        return true;
    }

    private boolean itemName(final Player p, final ItemStack held, final String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("none")) {
            held.editMeta(meta -> meta.displayName(null));
            p.sendMessage(plugin.msg("itemname-cleared"));
            return true;
        }
        // Items render their name italic unless told otherwise, which is never what was typed.
        final Component name = Format.body(String.join(" ", args), STAFF)
                .decoration(TextDecoration.ITALIC, false);
        held.editMeta(meta -> meta.displayName(name));
        p.sendMessage(plugin.msg("itemname", Placeholder.component("name", name)));
        return true;
    }

    private boolean itemLore(final Player p, final ItemStack held, final String[] args) {
        if (args.length == 0) {
            p.sendMessage(plugin.msg("itemlore-usage"));
            return true;
        }
        final List<Component> lore = new ArrayList<>(
                held.getItemMeta().hasLore() ? held.getItemMeta().lore() : List.of());
        final String action = args[0].toLowerCase(Locale.ROOT);
        final String rest = args.length > 1 ? String.join(" ", List.of(args).subList(1, args.length)) : "";

        switch (action) {
            case "clear" -> lore.clear();
            case "add" -> {
                if (lore.size() >= maxLoreLines
                        && !Perms.may(p, "kremlin.itemlore.bypass", "essentials.itemlore.bypass")) {
                    p.sendMessage(plugin.msg("itemlore-too-many",
                            Placeholder.unparsed("max", String.valueOf(maxLoreLines))));
                    return true;
                }
                lore.add(line(rest));
            }
            case "set", "remove" -> {
                final int at;
                try {
                    at = Integer.parseInt(rest.isEmpty() ? args[1] : rest.split("\\s+", 2)[0]) - 1;
                } catch (final RuntimeException ex) {
                    p.sendMessage(plugin.msg("itemlore-usage"));
                    return true;
                }
                if (at < 0 || at >= lore.size()) {
                    p.sendMessage(plugin.msg("itemlore-no-line",
                            Placeholder.unparsed("line", String.valueOf(at + 1))));
                    return true;
                }
                if (action.equals("remove")) {
                    lore.remove(at);
                } else {
                    final String[] parts = rest.split("\\s+", 2);
                    lore.set(at, line(parts.length > 1 ? parts[1] : ""));
                }
            }
            default -> {
                p.sendMessage(plugin.msg("itemlore-usage"));
                return true;
            }
        }
        held.editMeta(meta -> meta.lore(lore));
        p.sendMessage(plugin.msg("itemlore", Placeholder.unparsed("count", String.valueOf(lore.size()))));
        return true;
    }

    private static Component line(final String text) {
        return Format.body(text, STAFF).decoration(TextDecoration.ITALIC, false);
    }

    private boolean skull(final Player p, final String[] args) {
        final boolean others = args.length > 0 && !args[0].equalsIgnoreCase(p.getName());
        final String node = others ? "skull.others" : "skull";
        if (!Perms.may(p, "kremlin." + node, "essentials." + node)) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        final String wanted = args.length > 0 ? args[0] : p.getName();
        final UUID id = plugin.findPlayer(wanted);
        final OfflinePlayer owner = id == null ? null : plugin.getServer().getOfflinePlayer(id);
        if (owner == null) {
            p.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", wanted)));
            return true;
        }

        // Holding a head means "make this one theirs"; holding anything else means "give me one".
        final ItemStack held = p.getInventory().getItemInMainHand();
        final ItemStack skull = held.getType() == Material.PLAYER_HEAD ? held : ItemStack.of(Material.PLAYER_HEAD);
        skull.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        if (skull != held) give(p, skull);

        p.sendMessage(plugin.msg("skull", Placeholder.component("player", plugin.displayName(owner))));
        return true;
    }

    // ---------------------------------------------------------------- inventory

    private boolean give(final CommandSender sender, final String[] args) {
        if (!Perms.may(sender, "kremlin.give", "essentials.give")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("give-usage"));
            return true;
        }
        final Player target = Names.resolve(plugin.getServer(), args[0]);
        if (target == null) {
            sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
            return true;
        }
        // matchMaterial already understands "diamond_sword", "DIAMOND_SWORD" and the namespaced
        // form, so there is no parser here to get wrong.
        final Material material = Material.matchMaterial(args[1]);
        if (material == null || !material.isItem()) {
            sender.sendMessage(plugin.msg("give-unknown", Placeholder.unparsed("item", args[1])));
            return true;
        }

        int amount = defaultStackSize < 1 ? material.getMaxStackSize() : defaultStackSize;
        if (args.length > 2) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (final NumberFormatException ex) {
                sender.sendMessage(plugin.msg("give-usage"));
                return true;
            }
        }
        final int cap = Perms.may(sender, "kremlin.oversizedstacks", "essentials.oversizedstacks")
                ? oversizedStackSize : material.getMaxStackSize();
        amount = Math.max(1, Math.min(amount, cap));

        give(target, ItemStack.of(material, amount));
        sender.sendMessage(plugin.msg("give", Placeholder.unparsed("count", String.valueOf(amount)),
                Placeholder.unparsed("item", material.getKey().getKey()),
                Placeholder.component("player", plugin.displayName(target))));
        return true;
    }

    /** Into their inventory, or at their feet when there is no room -- never silently dropped. */
    private void give(final Player target, final ItemStack stack) {
        target.getInventory().addItem(stack).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
    }

    private boolean clear(final CommandSender sender, final String[] args) {
        final boolean everyone = args.length > 0 && args[0].equals("*");
        final boolean others = args.length > 0 && !everyone;
        final String node = everyone ? "clearinventory.all"
                : others ? "clearinventory.others" : "clearinventory";
        if (!Perms.may(sender, "kremlin." + node, "essentials." + node)) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }

        final List<Player> targets = new ArrayList<>();
        if (everyone) {
            targets.addAll(plugin.getServer().getOnlinePlayers());
        } else if (others) {
            final Player target = Names.resolve(plugin.getServer(), args[0]);
            if (target == null) {
                sender.sendMessage(plugin.msg("msg-unknown", Placeholder.unparsed("player", args[0])));
                return true;
            }
            targets.add(target);
        } else if (sender instanceof Player p) {
            targets.add(p);
        } else {
            sender.sendMessage(plugin.msg("console-needs-player"));
            return true;
        }

        final Material only = args.length > 1 && !args[1].equals("*") ? Material.matchMaterial(args[1]) : null;
        if (args.length > 1 && !args[1].equals("*") && only == null) {
            sender.sendMessage(plugin.msg("give-unknown", Placeholder.unparsed("item", args[1])));
            return true;
        }

        for (final Player target : targets) {
            // The owning region has to do the clearing, which for /clearinventory * is many
            // different regions -- hence a hop per player rather than one loop on ours.
            target.getScheduler().run(plugin.owner(), t -> {
                if (only == null) target.getInventory().clear();
                else target.getInventory().remove(only);
                if (!target.equals(sender)) target.sendMessage(plugin.msg("clearinventory-done"));
            }, null);
        }
        sender.sendMessage(plugin.msg("clearinventory",
                Placeholder.unparsed("count", String.valueOf(targets.size()))));
        return true;
    }

    /**
     * A window with nothing behind it. Anything left in it when it closes is gone, which is the
     * entire feature -- there is deliberately no storage here to leak.
     */
    private boolean trash(final Player p) {
        if (!Perms.may(p, "kremlin.trash", "essentials.disposal")) {
            p.sendMessage(plugin.msg("chat-no-permission"));
            return true;
        }
        p.openInventory(plugin.getServer().createInventory(null, 36, plugin.msg("trash-title")));
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String label, final String[] args) {
        final String cmd = Combat.rootCommand(label);
        final List<String> online = Names.online(plugin.getServer(), List.of());

        if (REPAIR.contains(cmd)) {
            return args.length == 1 ? Names.filter(args[0], List.of("hand", "all")) : List.of();
        }
        if (ITEMLORE.contains(cmd)) {
            return args.length == 1 ? Names.filter(args[0], List.of("add", "set", "remove", "clear")) : List.of();
        }
        if (SKULL.contains(cmd)) {
            return args.length == 1 ? Names.filter(args[0], online) : List.of();
        }
        if (GIVE.contains(cmd)) {
            if (args.length == 1) return Names.filter(args[0], online);
            return args.length == 2 ? Names.filter(args[1], items()) : List.of();
        }
        if (CLEAR.contains(cmd)) {
            if (args.length == 1) {
                final List<String> options = new ArrayList<>(online);
                options.add("*");
                return Names.filter(args[0], options);
            }
            return args.length == 2 ? Names.filter(args[1], items()) : List.of();
        }
        return List.of();
    }

    private static List<String> items() {
        final List<String> names = new ArrayList<>();
        for (final Material m : Material.values()) {
            if (m.isItem() && !m.isLegacy()) names.add(m.getKey().getKey());
        }
        return names;
    }
}
