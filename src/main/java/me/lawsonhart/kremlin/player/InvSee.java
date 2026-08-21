package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;
import me.lawsonhart.kremlin.core.Perms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /invsee and /enderchest, replacing Essentials'. Declared in plugin.yml and dispatched here
 * normally, like {@link Homes} and {@link Tpa}. Essentials' own e-prefixed labels (/einvsee,
 * /eec) are carried as aliases so muscle memory still lands somewhere.
 *
 * Offline players work because we keep a snapshot of everyone's inventory and ender chest, taken
 * when they log out (and for everyone still on at shutdown). Editing an offline player's snapshot
 * queues it: the moment they log back in, the edit is written onto them.
 *
 * That snapshot is deliberately how offline access is done here rather than reaching into the
 * server's playerdata files. Kremlin is folia-supported, and Folia owns player data per region
 * thread -- loading somebody else's .dat behind the server's back is exactly the cross-thread read
 * Folia exists to forbid. It costs one coverage gap: a player who has not logged out once since
 * Kremlin was installed has no snapshot yet, and /invsee says so plainly rather than showing an
 * empty inventory that looks real.
 *
 * The same snapshot-and-apply path is used for online players too, hopped onto the target's own
 * region scheduler in both directions, so there is one code path rather than two.
 */
public final class InvSee implements Listener, CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Every label either command answers to, the e-prefixed Essentials fallbacks included. */
    public static final Set<String> INVSEE = Set.of("invsee", "einvsee", "openinv", "seeinv");
    public static final Set<String> ENDER = Set.of(
            "enderchest", "ec", "echest", "enderc", "endersee", "invseeender",
            "eenderchest", "eechest", "eec", "eenderc");

    /** 0-35 main, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 off-hand. */
    static final int SLOTS = 41;
    static final int ENDER_SLOTS = 27;

    static final int GUI_SIZE = 45;
    private static final int INFO_SLOT = 44;

    /**
     * Chest slot -> snapshot index, -1 for the filler. Laid out like the player's own screen:
     * main inventory on top, hotbar under it, then armour helmet-to-boots and the off-hand.
     */
    static final int[] GUI = new int[GUI_SIZE];

    static {
        Arrays.fill(GUI, -1);
        for (int i = 0; i < 27; i++) GUI[i] = i + 9;
        for (int i = 0; i < 9; i++) GUI[27 + i] = i;
        GUI[36] = 39; // helmet
        GUI[37] = 38; // chestplate
        GUI[38] = 37; // leggings
        GUI[39] = 36; // boots
        GUI[40] = 40; // off-hand
    }

    private final Combat plugin;
    private final File file;

    /** uuid -> last known inventory / ender chest. Only consulted while they're offline. */
    private final Map<UUID, ItemStack[]> inventories = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> enders = new ConcurrentHashMap<>();
    /** Health, hunger and effects, for the info item. Read-only -- /invsee never writes these. */
    private final Map<UUID, Status> status = new ConcurrentHashMap<>();
    /** uuid -> last known name, so /invsee works on someone who isn't on right now. */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** What the player looked like, as opposed to what they were carrying. */
    record Status(double health, double maxHealth, int food, List<PotionEffect> effects) {}

    /** Edits made while they were offline, waiting for their next join. */
    private final Set<UUID> pendingInventory = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingEnder = ConcurrentHashMap.newKeySet();

    public InvSee(Combat plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "inventories.yml");
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        inventories.clear();
        enders.clear();
        status.clear();
        names.clear();
        pendingInventory.clear();
        pendingEnder.clear();
        if (!file.exists()) return;

        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = y.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection sec = players.getConfigurationSection(key);
            if (sec == null) continue;
            String name = sec.getString("name");
            if (name != null) names.put(id, name);
            inventories.put(id, decode(sec.getString("inventory"), SLOTS));
            enders.put(id, decode(sec.getString("ender"), ENDER_SLOTS));
            if (sec.contains("health")) {
                List<PotionEffect> effects = new ArrayList<>();
                for (Object raw : sec.getList("effects", List.of())) {
                    if (raw instanceof PotionEffect effect) effects.add(effect);
                }
                status.put(id, new Status(sec.getDouble("health"), sec.getDouble("max-health", 20.0),
                        sec.getInt("food"), effects));
            }
            if (sec.getBoolean("apply-inventory")) pendingInventory.add(id);
            if (sec.getBoolean("apply-ender")) pendingEnder.add(id);
        }
        plugin.getLogger().info("Loaded inventory snapshots for " + inventories.size() + " player(s).");
    }

    /** ponytail: rewrites the whole file per change, like homes.yml. Logouts are rare events. */
    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (UUID id : names.keySet()) {
            String base = "players." + id;
            y.set(base + ".name", names.get(id));
            ItemStack[] inv = inventories.get(id);
            if (inv != null) y.set(base + ".inventory", encode(inv));
            ItemStack[] ec = enders.get(id);
            if (ec != null) y.set(base + ".ender", encode(ec));
            Status st = status.get(id);
            if (st != null) {
                y.set(base + ".health", st.health());
                y.set(base + ".max-health", st.maxHealth());
                y.set(base + ".food", st.food());
                y.set(base + ".effects", st.effects());
            }
            if (pendingInventory.contains(id)) y.set(base + ".apply-inventory", true);
            if (pendingEnder.contains(id)) y.set(base + ".apply-ender", true);
        }
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save inventories.yml: " + ex);
        }
    }

    private void dirty() {
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> save());
    }

    /**
     * Full NBT round-trip rather than YAML's ItemStack serialisation: an admin opening /invsee
     * must not quietly strip data components off somebody's gear on the way through.
     */
    static String encode(ItemStack[] items) {
        ItemStack[] safe = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) safe[i] = items[i] == null ? ItemStack.empty() : items[i];
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(safe));
    }

    private ItemStack[] decode(String encoded, int size) {
        ItemStack[] out = new ItemStack[size];
        if (encoded == null || encoded.isEmpty()) return out;
        try {
            ItemStack[] in = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
            for (int i = 0; i < Math.min(size, in.length); i++) out[i] = copy(in[i]);
        } catch (Throwable t) {
            // A snapshot written by an older server can fail to read back. Losing one snapshot is
            // survivable; refusing to load the whole file over one bad entry is not.
            plugin.getLogger().warning("Unreadable inventory snapshot, dropping it: " + t);
        }
        return out;
    }

    static ItemStack copy(ItemStack s) {
        return s == null || s.getType().isAir() ? null : s.clone();
    }

    // ---------------------------------------------------------------- live inventory

    /**
     * Read through the typed accessors rather than raw slot indices: the armour and off-hand
     * index numbers have moved between versions, the accessors haven't.
     */
    static ItemStack[] read(PlayerInventory inv) {
        ItemStack[] out = new ItemStack[SLOTS];
        for (int i = 0; i < 36 && i < inv.getSize(); i++) out[i] = copy(inv.getItem(i));
        out[36] = copy(inv.getBoots());
        out[37] = copy(inv.getLeggings());
        out[38] = copy(inv.getChestplate());
        out[39] = copy(inv.getHelmet());
        out[40] = copy(inv.getItemInOffHand());
        return out;
    }

    static void write(PlayerInventory inv, ItemStack[] c) {
        for (int i = 0; i < 36 && i < inv.getSize(); i++) inv.setItem(i, c[i]);
        inv.setBoots(c[36]);
        inv.setLeggings(c[37]);
        inv.setChestplate(c[38]);
        inv.setHelmet(c[39]);
        inv.setItemInOffHand(c[40] == null ? ItemStack.empty() : c[40]);
    }

    private static ItemStack[] read(Inventory inv, int size) {
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size && i < inv.getSize(); i++) out[i] = copy(inv.getItem(i));
        return out;
    }

    private static void write(Inventory inv, ItemStack[] c) {
        for (int i = 0; i < c.length && i < inv.getSize(); i++) inv.setItem(i, c[i]);
    }

    // ---------------------------------------------------------------- capture / apply

    /** Snapshot someone. Must already be running on that player's region. */
    private void capture(Player p) {
        UUID id = p.getUniqueId();
        names.put(id, p.getName());
        inventories.put(id, read(p.getInventory()));
        enders.put(id, read(p.getEnderChest(), ENDER_SLOTS));
        status.put(id, statusOf(p));
    }

    /** Must be read on that player's own region, like anything else about them. */
    static Status statusOf(Player p) {
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        return new Status(p.getHealth(), max == null ? 20.0 : max.getValue(), p.getFoodLevel(),
                List.copyOf(p.getActivePotionEffects()));
    }

    /**
     * MONITOR so the combat-log punishment in {@link Combat} has already emptied them: someone who
     * logged out mid-fight really has lost that inventory, and the snapshot must agree.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        capture(e.getPlayer());
        dirty();
    }

    /** Anything an admin changed while they were away lands on them here. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boolean inv = pendingInventory.remove(id);
        boolean ec = pendingEnder.remove(id);
        names.put(id, p.getName());
        if (!inv && !ec) return;
        if (inv) write(p.getInventory(), inventories.getOrDefault(id, new ItemStack[SLOTS]));
        if (ec) write(p.getEnderChest(), enders.getOrDefault(id, new ItemStack[ENDER_SLOTS]));
        p.sendMessage(plugin.msg("invsee-applied"));
        dirty();
    }

    /** Shutdown: everyone still on gets a fresh snapshot, so a restart doesn't leave stale ones. */
    public void saveNow() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            try {
                capture(p);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not snapshot " + p.getName() + " on shutdown: " + t);
            }
        }
        save();
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- this one opens a window."));
            return true;
        }
        boolean ender = ENDER.contains(Combat.rootCommand(label));
        String arg = args.length > 0 ? args[0] : null;

        if (ender && arg == null) {
            ownEnderChest(p);
        } else if (arg == null) {
            p.sendMessage(plugin.msg("invsee-usage"));
        } else {
            open(p, arg, ender);
        }
        return true;
    }

    /** Only who we actually hold a snapshot for, plus everyone currently on. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !(sender instanceof Player p)) return List.of();
        String[] nodes = ENDER.contains(Combat.rootCommand(label))
                ? new String[]{"kremlin.enderchest.others", "essentials.enderchest.others"}
                : new String[]{"kremlin.invsee", "essentials.invsee"};
        if (!Perms.may(p, nodes)) return List.of();
        return Names.filter(args[0], Names.online(plugin.getServer(), knownNames()));
    }

    /**
     * /enderchest on its own is still your own ender chest, and it is still a restock in the
     * middle of a fight -- so it keeps the combat gate it has in blocked-commands. Cancelling at
     * LOWEST means Combat's own listener never sees this command, so the check happens here.
     */
    private void ownEnderChest(Player p) {
        if (!Perms.may(p, "kremlin.enderchest", "essentials.enderchest")) {
            p.sendMessage(plugin.msg("invsee-no-permission"));
            return;
        }
        Component deny = plugin.denyCombat(p);
        if (deny != null) {
            p.sendMessage(deny);
            return;
        }
        p.openInventory(p.getEnderChest());
    }

    private void open(Player viewer, String query, boolean ender) {
        boolean allowed = ender
                ? Perms.may(viewer, "kremlin.enderchest.others", "essentials.enderchest.others")
                : Perms.may(viewer, "kremlin.invsee", "essentials.invsee");
        if (!allowed) {
            viewer.sendMessage(plugin.msg("invsee-no-permission"));
            return;
        }
        UUID target = resolve(query);
        if (target == null) {
            viewer.sendMessage(plugin.msg("home-unknown-player", Placeholder.unparsed("player", query)));
            return;
        }
        Player online = plugin.getServer().getPlayer(target);
        if (online != null) {
            // Folia: their inventory belongs to their region, so read it there and open ours on
            // ours. A copy crosses the thread boundary, never the live inventory object.
            online.getScheduler().run(plugin.owner(), t -> {
                ItemStack[] contents = ender
                        ? read(online.getEnderChest(), ENDER_SLOTS)
                        : read(online.getInventory());
                String name = online.getName();
                Status live = statusOf(online);
                status.put(target, live);
                viewer.getScheduler().run(plugin.owner(),
                        t2 -> show(viewer, target, name, contents, ender, true, live), null);
            }, null);
            return;
        }

        String name = names.get(target);
        ItemStack[] stored = ender ? enders.get(target) : inventories.get(target);
        if (stored == null) {
            viewer.sendMessage(plugin.msg("invsee-no-data",
                    Placeholder.unparsed("player", name == null ? query : name)));
            return;
        }
        show(viewer, target, name == null ? query : name, stored.clone(), ender, false,
                status.get(target));
    }

    /** Everyone we hold a snapshot for. What /invsee can actually be pointed at while offline. */
    public Collection<String> knownNames() {
        return names.values();
    }

    /** Online (real name or nickname) first, then anyone we've snapshotted, then homes/Mojang. */
    UUID resolve(String query) {
        Player online = Names.resolve(plugin.getServer(), query);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(query)) return e.getKey();
        }
        return plugin.getHomes().findPlayer(query);
    }

    // ---------------------------------------------------------------- gui

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        UUID target;
        String targetName;
        boolean ender;
        boolean live;
        /** What we put in the menu. Anything that differs from this is the admin's doing. */
        ItemStack[] shown;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private void show(Player viewer, UUID target, String name, ItemStack[] contents,
                      boolean ender, boolean live, Status st) {
        Menu menu = new Menu();
        menu.target = target;
        menu.targetName = name;
        menu.ender = ender;
        menu.live = live;

        // The window title and the queued notice both name a real player we have resolved, so
        // both carry their nickname as it is actually shown rather than flattened to text.
        Component shown = plugin.displayName(plugin.getServer().getOfflinePlayer(target));
        Component title = plugin.msg(ender
                ? (live ? "enderchest-title" : "enderchest-title-offline")
                : (live ? "invsee-title" : "invsee-title-offline"), Placeholder.component("player", shown));
        Inventory inv = plugin.getServer().createInventory(menu, ender ? ENDER_SLOTS : GUI_SIZE, title);
        menu.inv = inv;
        menu.shown = contents.clone();

        if (ender) {
            write(inv, contents);
        } else {
            for (int slot = 0; slot < GUI_SIZE; slot++) {
                if (GUI[slot] >= 0) inv.setItem(slot, contents[GUI[slot]]);
                else if (slot != INFO_SLOT) inv.setItem(slot, filler());
            }
            inv.setItem(INFO_SLOT, info(name, live, st));
        }

        viewer.openInventory(inv);
        if (!live) viewer.sendMessage(plugin.msg("invsee-queued", Placeholder.component("player", shown)));
    }

    private static ItemStack filler() {
        return decorate(ItemStack.of(Material.GRAY_STAINED_GLASS_PANE), "<dark_gray>-", List.of());
    }

    private static ItemStack info(String name, boolean live, Status st) {
        List<String> lore = new ArrayList<>();
        lore.add(live ? "<green>Online" : "<yellow>Offline <dark_gray>- as they logged out");
        lore.add("");
        if (st == null) {
            lore.add("<dark_gray>No health or effects recorded");
        } else {
            lore.add("<red>Health <white>" + num(st.health()) + "<dark_gray>/<gray>" + num(st.maxHealth()));
            lore.add("<gold>Food <white>" + st.food() + "<dark_gray>/<gray>20");
            lore.add("");
            if (st.effects().isEmpty()) {
                lore.add("<gray>Effects: <dark_gray>none");
            } else {
                lore.add("<gray>Effects:");
                // A beacon plus a potion or two is a handful; a stack of them is a wall of lore.
                st.effects().stream().limit(8).forEach(e -> lore.add("  " + effect(e)));
                if (st.effects().size() > 8) {
                    lore.add("  <dark_gray>+" + (st.effects().size() - 8) + " more");
                }
            }
        }
        lore.add("");
        lore.add(live ? "<dark_gray>Edits apply instantly" : "<dark_gray>Edits apply on next join");
        return decorate(ItemStack.of(Material.PAPER), "<gold>" + name, lore);
    }

    /** "Fire Resistance II 1:23". Effect keys are registry names, so they are safe as markup. */
    static String effect(PotionEffect e) {
        StringBuilder out = new StringBuilder("<light_purple>");
        for (String word : e.getType().getKey().getKey().split("_")) {
            if (word.isEmpty()) continue;
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        if (e.getAmplifier() > 0) out.append(roman(e.getAmplifier() + 1)).append(' ');
        return out.append("<dark_gray>").append(e.isInfinite() ? "forever" : clock(e.getDuration() / 20)).toString();
    }

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    static String roman(int level) {
        return level >= 1 && level <= ROMAN.length ? ROMAN[level - 1] : String.valueOf(level);
    }

    static String clock(int seconds) {
        return Math.max(0, seconds / 60) + ":" + String.format("%02d", Math.max(0, seconds % 60));
    }

    /** 18.5 stays 18.5, 20.0 shows as 20 -- nobody writes their health with a trailing zero. */
    static String num(double value) {
        String out = String.format("%.1f", value);
        return out.endsWith(".0") ? out.substring(0, out.length() - 2) : out;
    }

    private static ItemStack decorate(ItemStack stack, String name, List<String> lore) {
        stack.editMeta(meta -> {
            meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(l -> MM.deserialize(l).decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return stack;
    }

    /**
     * Clicks are allowed -- that's the point of this menu -- except on the filler, which is
     * scenery. Anything the admin does gets pushed a tick later, once the click has resolved.
     */
    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        if (!(e.getWhoClicked() instanceof Player viewer)) return;
        if (!menu.ender && e.getClickedInventory() == menu.inv
                && e.getSlot() >= 0 && e.getSlot() < GUI_SIZE && GUI[e.getSlot()] < 0) {
            e.setCancelled(true);
            return;
        }
        push(viewer, menu);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        if (!(e.getWhoClicked() instanceof Player viewer)) return;
        if (!menu.ender) {
            for (int slot : e.getRawSlots()) {
                if (slot < GUI_SIZE && GUI[slot] < 0) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
        push(viewer, menu);
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        if (e.getPlayer() instanceof Player viewer) push(viewer, menu);
    }

    /**
     * Read the menu back and apply it.
     *
     * Only the slots the admin actually changed are written. A menu can sit open for minutes,
     * and pushing the whole thing back would undo every block the target mined in the meantime
     * -- the admin's copy is stale everywhere they didn't touch it.
     */
    private void push(Player viewer, Menu menu) {
        viewer.getScheduler().runDelayed(plugin.owner(), t -> {
            ItemStack[] now;
            if (menu.ender) {
                now = read(menu.inv, ENDER_SLOTS);
            } else {
                now = new ItemStack[SLOTS];
                for (int slot = 0; slot < GUI_SIZE; slot++) {
                    if (GUI[slot] >= 0) now[GUI[slot]] = copy(menu.inv.getItem(slot));
                }
                restoreFiller(viewer, menu.inv);
            }

            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < now.length; i++) {
                if (!Objects.equals(now[i], menu.shown[i])) changed.add(i);
            }
            if (changed.isEmpty()) return;
            // Taken as applied: a later click must not replay these over fresher contents.
            menu.shown = now.clone();
            apply(menu, now, changed);
        }, null, 1L);
    }

    /** An item shift-clicked into the scenery would otherwise vanish on write-back. */
    private void restoreFiller(Player viewer, Inventory inv) {
        for (int slot = 0; slot < GUI_SIZE; slot++) {
            if (GUI[slot] >= 0 || slot == INFO_SLOT) continue;
            ItemStack stray = inv.getItem(slot);
            if (stray != null && stray.getType() != Material.GRAY_STAINED_GLASS_PANE) {
                for (ItemStack leftover : viewer.getInventory().addItem(stray).values()) {
                    viewer.getWorld().dropItemNaturally(viewer.getLocation(), leftover);
                }
            }
            inv.setItem(slot, filler());
        }
    }

    /** Merge the changed slots onto whatever the target has right now, and store the result. */
    private static ItemStack[] merge(ItemStack[] current, ItemStack[] edited, List<Integer> changed) {
        for (int i : changed) {
            if (i < current.length) current[i] = edited[i];
        }
        return current;
    }

    private void apply(Menu menu, ItemStack[] edited, List<Integer> changed) {
        UUID id = menu.target;
        names.putIfAbsent(id, menu.targetName);

        Player online = plugin.getServer().getPlayer(id);
        if (online == null) {
            storeOffline(id, menu.ender, edited, changed);
            return;
        }
        online.getScheduler().run(plugin.owner(),
                t -> {
                    if (menu.ender) {
                        ItemStack[] merged = merge(read(online.getEnderChest(), ENDER_SLOTS), edited, changed);
                        write(online.getEnderChest(), merged);
                        enders.put(id, merged);
                    } else {
                        ItemStack[] merged = merge(read(online.getInventory()), edited, changed);
                        write(online.getInventory(), merged);
                        inventories.put(id, merged);
                    }
                },
                // Retired: they logged off between the read and the write. Queue it instead.
                () -> storeOffline(id, menu.ender, edited, changed));
    }

    private void storeOffline(UUID id, boolean ender, ItemStack[] edited, List<Integer> changed) {
        Map<UUID, ItemStack[]> store = ender ? enders : inventories;
        int size = ender ? ENDER_SLOTS : SLOTS;
        store.put(id, merge(store.getOrDefault(id, new ItemStack[size]).clone(), edited, changed));
        queue(id, ender);
    }

    private void queue(UUID id, boolean ender) {
        if (ender) pendingEnder.add(id);
        else pendingInventory.add(id);
        dirty();
    }

    public String describe() {
        return "snapshots " + names.size() + " | queued "
                + (pendingInventory.size() + pendingEnder.size());
    }
}
