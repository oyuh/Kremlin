package me.lawsonhart.kremlin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Homes, replacing Essentials'. Same override trick as {@link Tpa}: every home command is
 * cancelled in PlayerCommandPreprocessEvent at LOWEST priority, so Essentials never sees it
 * and /essentials:home is closed off by the same namespace-stripping.
 *
 * The team HQ is not here: SimpleTeams owns /team home and its /hq shortcut outright, and
 * Kremlin only gates it (combat block, warmup) through TeamHomeTeleportEvent. The menu's HQ
 * button is a shortcut that calls that plugin's own teleport -- no team logic on this side.
 *
 * Old Essentials permission nodes keep working everywhere -- essentials.home, essentials.sethome,
 * essentials.delhome, essentials.home.others, and essentials.sethome.multiple.N for limits --
 * so nobody has to redo their LuckPerms setup. So do CombatPrev's own combatprev.* nodes, which
 * are accepted alongside the kremlin.* names.
 */
public final class Homes implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    static final Set<String> GO = Set.of("home", "homes", "listhomes");
    private static final Set<String> SET = Set.of("sethome", "createhome");
    static final Set<String> DEL = Set.of("delhome", "remhome", "removehome");
    static final Set<String> RENAME = Set.of("renamehome", "homerename", "movehome");
    static final Set<String> ADMIN = Set.of("adminhome", "homeadmin", "homeof");
    private static final Set<String> HUB_GO = Set.of("comhub", "communityhub");
    private static final Set<String> HUB_SET = Set.of("setcomhub", "setcommunityhub");
    private static final Set<String> HUB_DEL = Set.of("delcomhub", "delcommunityhub");

    /** The bed home isn't stored -- it's whatever vanilla already tracks. */
    static final String BED = "bed";
    /** Not a home we keep: SimpleTeams owns the team HQ. Reserved so nobody shadows /hq. */
    static final String HQ = "hq";
    /** The one server-wide destination, set by staff with /setcomhub. Reserved like the other two. */
    static final String HUB = "hub";

    /** Names nobody may take for a home of their own -- each already means something else. */
    static boolean reserved(String name) {
        return name.equalsIgnoreCase(BED) || name.equalsIgnoreCase(HQ) || name.equalsIgnoreCase(HUB);
    }

    private final Combat plugin;
    private final TeamHook teams;
    private final File file;

    /** owner uuid -> (home name -> location). TreeMap so the GUI order is stable. */
    private final Map<UUID, Map<String, Location>> homes = new ConcurrentHashMap<>();
    /** last known name per uuid, so /adminhome works on offline players */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    /** Who we're waiting on a chat message from, and which home they're renaming. */
    private record Pending(UUID owner, String home, long expires) {}

    private final Map<UUID, Pending> awaitingName = new ConcurrentHashMap<>();
    /** Who has shift-clicked a home once, and which one. A second click inside the window deletes. */
    private final Map<UUID, Pending> awaitingDelete = new ConcurrentHashMap<>();

    private static final long CONFIRM_MILLIS = 5_000L;

    private int defaultMax;

    /** The community hub, or null until an admin sets one. Server-wide, not per player. */
    private volatile Location communityHub;

    Homes(Combat plugin, TeamHook teams) {
        this.plugin = plugin;
        this.teams = teams;
        this.file = new File(plugin.getDataFolder(), "homes.yml");
    }

    // ---------------------------------------------------------------- storage

    void load() {
        defaultMax = plugin.getConfig().getInt("homes.max-per-player", 3);
        homes.clear();
        names.clear();
        communityHub = null;
        if (!file.exists()) return;

        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        Location hub = y.getLocation("community-hub");
        if (hub != null && hub.getWorld() == null) {
            plugin.getLogger().warning("The community hub's world isn't loaded, ignoring it.");
            hub = null;
        }
        communityHub = hub;
        ConfigurationSection players = y.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID id = parseUuid(key);
                if (id == null) continue;
                ConfigurationSection sec = players.getConfigurationSection(key);
                if (sec == null) continue;
                String name = sec.getString("name");
                if (name != null) names.put(id, name);
                ConfigurationSection list = sec.getConfigurationSection("homes");
                if (list == null) continue;
                Map<String, Location> mine = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (String home : list.getKeys(false)) {
                    Location loc = list.getLocation(home);
                    if (loc == null || loc.getWorld() == null) {
                        plugin.getLogger().warning("Home '" + home + "' for " + key + " has no loadable world, skipping.");
                        continue;
                    }
                    mine.put(home, loc);
                }
                if (!mine.isEmpty()) homes.put(id, mine);
            }
        }
        plugin.getLogger().info("Loaded homes for " + homes.size() + " player(s).");
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** ponytail: rewrites the file per change. Homes change a handful of times a minute at worst. */
    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        Location hub = communityHub;
        if (hub != null) y.set("community-hub", hub);
        for (Map.Entry<UUID, Map<String, Location>> e : homes.entrySet()) {
            String base = "players." + e.getKey();
            String name = names.get(e.getKey());
            if (name != null) y.set(base + ".name", name);
            for (Map.Entry<String, Location> h : e.getValue().entrySet()) {
                y.set(base + ".homes." + h.getKey(), h.getValue());
            }
        }
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save homes.yml: " + ex);
        }
    }

    private void dirty() {
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> save());
    }

    void saveNow() {
        save();
    }

    // ---------------------------------------------------------------- permissions

    /** Ours, CombatPrev's old node, or the Essentials one -- any of them grants it. */
    private static boolean may(Player p, String... nodes) {
        for (String node : nodes) {
            if (p.hasPermission(node)) return true;
        }
        return false;
    }

    /**
     * Home limit. Honours essentials.sethome.multiple.N as well as kremlin.homes.N and the old
     * combatprev.homes.N, taking the highest granted, so existing LuckPerms setups keep working.
     */
    int maxHomes(Player p) {
        if (may(p, "kremlin.homes.unlimited", "combatprev.homes.unlimited", "essentials.sethome.multiple.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int max = defaultMax;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            String tail = null;
            if (node.startsWith("kremlin.homes.")) tail = node.substring("kremlin.homes.".length());
            else if (node.startsWith("combatprev.homes.")) tail = node.substring("combatprev.homes.".length());
            else if (node.startsWith("essentials.sethome.multiple.")) tail = node.substring("essentials.sethome.multiple.".length());
            if (tail == null) continue;
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignored) {
                // essentials.sethome.multiple.<groupname> -- the count lives in Essentials' own
                // config, which we deliberately don't read. The numeric form is what we support.
            }
        }
        return max;
    }

    // ---------------------------------------------------------------- commands

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String cmd = Combat.rootCommand(e.getMessage());
        boolean ours = GO.contains(cmd) || SET.contains(cmd) || DEL.contains(cmd)
                || RENAME.contains(cmd) || ADMIN.contains(cmd)
                || HUB_GO.contains(cmd) || HUB_SET.contains(cmd) || HUB_DEL.contains(cmd);
        if (!ours) return;
        e.setCancelled(true);

        String[] parts = e.getMessage().trim().split("\\s+");
        String arg = parts.length > 1 ? parts[1] : null;
        Player p = e.getPlayer();

        if (HUB_GO.contains(cmd)) {
            goHub(p);
        } else if (HUB_SET.contains(cmd)) {
            setHub(p);
        } else if (HUB_DEL.contains(cmd)) {
            delHub(p);
        } else if (ADMIN.contains(cmd)) {
            adminOpen(p, arg);
        } else if (RENAME.contains(cmd)) {
            renameHome(p, p.getUniqueId(), arg, parts.length > 2 ? parts[2] : null);
        } else if (GO.contains(cmd)) {
            if (arg == null || cmd.equals("homes") || cmd.equals("listhomes")) openGui(p, p.getUniqueId(), false);
            else goHome(p, arg);
        } else if (SET.contains(cmd)) {
            setHome(p, arg == null ? "home" : arg);
        } else {
            if (arg == null) p.sendMessage(plugin.msg("home-usage-del"));
            else delHome(p, arg);
        }
    }

    // ---------------------------------------------------------------- player homes

    private Map<String, Location> mine(UUID id) {
        return homes.getOrDefault(id, Map.of());
    }

    /**
     * The bed home comes straight from vanilla, so it can never drift out of sync.
     *
     * getRespawnLocation() -- the no-arg form -- sync-loads the bed's chunk to validate it,
     * which on Folia throws unless you happen to be ticking the region that owns that chunk.
     * A bed is usually somewhere else entirely, so that crashed both /home and /adminhome.
     * The (false) overload skips the load and the validation; the teleport itself loads the
     * chunk properly on the right region anyway.
     */
    private Location bedOf(Player p) {
        try {
            return p.getRespawnLocation(false);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read bed home for " + p.getName() + ": " + t);
            return null;
        }
    }

    private void setHome(Player p, String rawName) {
        if (!may(p, "kremlin.sethome", "combatprev.sethome", "essentials.sethome")) {
            p.sendMessage(plugin.msg("home-no-permission"));
            return;
        }
        String name = clean(rawName);
        if (name.isEmpty() || reserved(name)) {
            p.sendMessage(plugin.msg("home-bad-name", Placeholder.unparsed("name", rawName)));
            return;
        }
        Component combat = plugin.denyCombat(p);
        if (combat != null) {
            p.sendMessage(combat);
            return;
        }
        Map<String, Location> mine = homes.computeIfAbsent(p.getUniqueId(),
                k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        int max = maxHomes(p);
        if (!mine.containsKey(name) && mine.size() >= max) {
            p.sendMessage(plugin.msg("home-limit", Placeholder.unparsed("max", String.valueOf(max))));
            return;
        }
        mine.put(name, p.getLocation().clone());
        names.put(p.getUniqueId(), p.getName());
        dirty();
        p.sendMessage(plugin.msg("home-set", Placeholder.unparsed("name", name),
                Placeholder.unparsed("count", String.valueOf(mine.size())),
                Placeholder.unparsed("max", max == Integer.MAX_VALUE ? "unlimited" : String.valueOf(max))));
    }

    private void delHome(Player p, String rawName) {
        if (!may(p, "kremlin.delhome", "combatprev.delhome", "essentials.delhome")) {
            p.sendMessage(plugin.msg("home-no-permission"));
            return;
        }
        String name = clean(rawName);
        Map<String, Location> mine = homes.get(p.getUniqueId());
        if (mine == null || mine.remove(name) == null) {
            p.sendMessage(plugin.msg("home-unknown", Placeholder.unparsed("name", rawName)));
            return;
        }
        dirty();
        p.sendMessage(plugin.msg("home-deleted", Placeholder.unparsed("name", name)));
    }

    private void renameHome(Player actor, UUID owner, String rawFrom, String rawTo) {
        Player p = actor;
        boolean self = owner.equals(actor.getUniqueId());
        boolean allowed = self
                ? may(actor, "kremlin.sethome", "combatprev.sethome", "essentials.sethome")
                : may(actor, "kremlin.home.others", "combatprev.home.others", "essentials.home.others");
        if (!allowed) {
            p.sendMessage(plugin.msg("home-no-permission"));
            return;
        }
        if (rawFrom == null || rawTo == null) {
            p.sendMessage(plugin.msg("home-usage-rename"));
            return;
        }
        String from = clean(rawFrom);
        String to = clean(rawTo);
        if (to.isEmpty() || reserved(to)) {
            p.sendMessage(plugin.msg("home-bad-name", Placeholder.unparsed("name", rawTo)));
            return;
        }
        Map<String, Location> mine = homes.get(owner);
        if (mine == null || !mine.containsKey(from)) {
            p.sendMessage(plugin.msg("home-unknown", Placeholder.unparsed("name", rawFrom)));
            return;
        }
        // Case-insensitive map, so renaming "Base" to "base" is a no-op rather than a clash.
        if (mine.containsKey(to) && !to.equalsIgnoreCase(from)) {
            p.sendMessage(plugin.msg("home-exists", Placeholder.unparsed("name", to)));
            return;
        }
        mine.put(to, mine.remove(from));
        dirty();
        p.sendMessage(plugin.msg("home-renamed",
                Placeholder.unparsed("name", from), Placeholder.unparsed("newname", to)));
    }

    private void goHome(Player p, String rawName) {
        if (!may(p, "kremlin.home", "combatprev.home", "essentials.home")) {
            p.sendMessage(plugin.msg("home-no-permission"));
            return;
        }
        String name = clean(rawName);
        if (name.equalsIgnoreCase(BED)) {
            Location bed = bedOf(p);
            if (bed == null) {
                p.sendMessage(plugin.msg("home-no-bed"));
                return;
            }
            travel(p, bed, BED);
            return;
        }
        Location target = mine(p.getUniqueId()).get(name);
        if (target == null) {
            p.sendMessage(plugin.msg("home-unknown", Placeholder.unparsed("name", rawName)));
            return;
        }
        travel(p, target, name);
    }

    // ---------------------------------------------------------------- community hub

    /**
     * One server-wide destination staff point at whatever the community is using right now --
     * a market, an event, spawn. It is not a home: there is only ever one, nobody can rename it
     * and it lives outside the per-player limit.
     *
     * The teleport itself is {@link #travel}, the same as every other button in this menu, so
     * the combat block and the warmup apply here exactly as they do to /home and /hq.
     */
    private void goHub(Player p) {
        Location hub = communityHub;
        if (hub == null) {
            p.sendMessage(plugin.msg("hub-none"));
            return;
        }
        travel(p, hub, HUB);
    }

    private void setHub(Player p) {
        if (!may(p, "kremlin.comhub.set", "combatprev.comhub.set", "essentials.setwarp")) {
            p.sendMessage(plugin.msg("home-no-permission"));
            return;
        }
        // Same reason /sethome is gated: pinning a destination mid-fight is still an escape.
        Component combat = plugin.denyCombat(p);
        if (combat != null) {
            p.sendMessage(combat);
            return;
        }
        communityHub = p.getLocation().clone();
        dirty();
        p.sendMessage(plugin.msg("hub-set"));
    }

    private void delHub(Player p) {
        if (!may(p, "kremlin.comhub.set", "combatprev.comhub.set", "essentials.delwarp")) {
            p.sendMessage(plugin.msg("home-no-permission"));
            return;
        }
        if (communityHub == null) {
            p.sendMessage(plugin.msg("hub-none"));
            return;
        }
        communityHub = null;
        dirty();
        p.sendMessage(plugin.msg("hub-deleted"));
    }

    /**
     * Homes keep the full stand-still gate -- they're the number one escape route -- and then
     * get the same warmup countdown as a tpa on top.
     */
    private void travel(Player p, Location destination, String label) {
        Component deny = plugin.denyTeleport(p);
        if (deny != null) {
            p.sendMessage(deny);
            return;
        }
        p.sendMessage(plugin.msg("home-going", Placeholder.unparsed("name", label)));
        plugin.startWarmup(p, destination.clone(), null, plugin.warmupFor(p));
    }

    static String clean(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    // ---------------------------------------------------------------- gui

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        UUID owner;
        boolean admin;
        final Map<Integer, String> slots = new LinkedHashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void openGui(Player viewer, UUID owner, boolean admin) {
        Menu menu = new Menu();
        menu.owner = owner;
        menu.admin = admin;

        String ownerName = names.getOrDefault(owner, "?");
        Player onlineOwner = plugin.getServer().getPlayer(owner);
        if (onlineOwner != null) ownerName = onlineOwner.getName();

        Inventory inv = plugin.getServer().createInventory(menu, 27,
                plugin.msg(admin ? "home-gui-title-admin" : "home-gui-title",
                        Placeholder.unparsed("player", ownerName)));
        menu.inv = inv;

        Location hub = communityHub;
        // Top-right is the hub's when there is one, so the homes stop one slot short of it.
        int lastHomeSlot = hub == null ? 8 : 7;

        int slot = 0;
        for (Map.Entry<String, Location> e : mine(owner).entrySet()) {
            if (slot > lastHomeSlot) break;
            inv.setItem(slot, item(Material.LODESTONE, "<yellow>" + e.getKey(), describe(e.getValue(), true)));
            menu.slots.put(slot, e.getKey());
            slot++;
        }
        if (slot == 0) {
            inv.setItem(4, item(Material.BARRIER, "<red>No homes set", List.of("<gray>/sethome <name>")));
        }

        // Community hub, top right. Shown in the admin view too -- it's the same one place
        // for everybody, not something the owner of these homes has any say over.
        if (hub != null) {
            inv.setItem(8, item(Material.NETHER_STAR, "<gold>Community Hub", List.of(
                    "<gray>" + hub.getWorld().getName() + " <dark_gray>" + hub.getBlockX() + " "
                            + hub.getBlockY() + " " + hub.getBlockZ(),
                    "",
                    "<green>Left-click to teleport")));
            menu.slots.put(8, HUB);
        }

        // Bed home: vanilla's respawn point, only resolvable for an online player.
        if (onlineOwner != null) {
            Location bed = bedOf(onlineOwner);
            inv.setItem(18, bed == null
                    ? item(Material.RED_BED, "<red>No bed home", List.of("<gray>Sleep in a bed to set it"))
                    : item(Material.RED_BED, "<light_purple>Bed", describe(bed, false)));
            if (bed != null) menu.slots.put(18, BED);
        }

        // A shortcut to /team home, nothing more. Only in your own menu: the API sends the
        // caller to their own team's home, so it would be meaningless in an admin view.
        if (!admin) {
            Location hq = teams.homeOf(owner);
            if (hq != null) {
                inv.setItem(26, item(Material.BEACON, "<aqua>Team HQ", List.of(
                        "<gray>" + hq.getWorld().getName() + " <dark_gray>" + hq.getBlockX() + " "
                                + hq.getBlockY() + " " + hq.getBlockZ(),
                        "",
                        "<green>Left-click to teleport",
                        "<dark_gray>Runs /team home")));
                menu.slots.put(26, HQ);
            }
        }

        viewer.openInventory(inv);
    }

    /** {@code editable} is false for the bed, which is vanilla's and has no name of its own. */
    private static List<String> describe(Location loc, boolean editable) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + loc.getWorld().getName() + " <dark_gray>" + loc.getBlockX() + " "
                + loc.getBlockY() + " " + loc.getBlockZ());
        lore.add("");
        lore.add("<green>Left-click to teleport");
        if (editable) {
            lore.add("<yellow>Right-click to rename");
            lore.add("<red>Shift-click to delete");
        }
        return lore;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String name = menu.slots.get(e.getRawSlot());
        if (name == null) return;

        if (HQ.equals(name)) {
            // Hand straight over to SimpleTeams. Its event comes back to our combat check and
            // warmup, so the button obeys the same rules as typing /team home.
            p.closeInventory();
            teams.teleportHome(p);
            return;
        }

        Location target = locate(menu.owner, name);
        if (target == null) {
            p.sendMessage(plugin.msg("home-unknown", Placeholder.unparsed("name", name)));
            return;
        }

        // Right-click renames, shift-click deletes -- both only apply to a home someone actually
        // owns. The bed and the community hub live elsewhere and are teleport-only.
        if (e.isRightClick() && !e.isShiftClick()) {
            if (reserved(name)) {
                p.sendMessage(plugin.msg("home-locked", Placeholder.unparsed("name", name)));
                return;
            }
            p.closeInventory();
            awaitingName.put(p.getUniqueId(), new Pending(menu.owner, name, System.currentTimeMillis() + 30_000L));
            p.sendMessage(plugin.msg("home-rename-prompt", Placeholder.unparsed("name", name)));
            p.sendMessage(plugin.msg("home-rename-cancel"));
            return;
        }

        if (e.isShiftClick()) {
            if (reserved(name)) {
                p.sendMessage(plugin.msg("home-locked", Placeholder.unparsed("name", name)));
                return;
            }
            confirmDelete(p, menu, name);
            return;
        }

        p.closeInventory();
        travel(p, target, name);
    }

    /**
     * Deleting is one shift-click to ask and a second to mean it. A misclick in a menu you opened
     * to teleport shouldn't be able to throw away a home -- and a home is the one thing here with
     * no undo, since the coordinates go with it.
     *
     * The confirmation is per home, so shift-clicking a different one starts over rather than
     * arming the wrong deletion.
     */
    private void confirmDelete(Player p, Menu menu, String name) {
        Pending armed = awaitingDelete.get(p.getUniqueId());
        boolean same = armed != null && armed.owner().equals(menu.owner) && armed.home().equals(name)
                && System.currentTimeMillis() <= armed.expires();
        if (!same) {
            awaitingDelete.put(p.getUniqueId(),
                    new Pending(menu.owner, name, System.currentTimeMillis() + CONFIRM_MILLIS));
            p.sendMessage(plugin.msg("home-delete-confirm", Placeholder.unparsed("name", name),
                    Placeholder.unparsed("time", String.valueOf(CONFIRM_MILLIS / 1000))));
            return;
        }
        awaitingDelete.remove(p.getUniqueId());
        if (!menuDelete(p, menu.owner, name)) return;
        // Redraw so the deleted home is actually gone from under their cursor. Next tick, not
        // now: opening an inventory from inside a click event leaves the client holding a ghost.
        p.getScheduler().run(plugin.owner(), t -> openGui(p, menu.owner, menu.admin), null);
    }

    /** Delete from the menu, for your own homes or -- with the admin node -- somebody else's. */
    private boolean menuDelete(Player actor, UUID owner, String name) {
        boolean self = owner.equals(actor.getUniqueId());
        boolean allowed = self
                ? may(actor, "kremlin.delhome", "combatprev.delhome", "essentials.delhome")
                : may(actor, "kremlin.home.others", "combatprev.home.others", "essentials.home.others");
        if (!allowed) {
            actor.sendMessage(plugin.msg("home-no-permission"));
            return false;
        }
        Map<String, Location> mine = homes.get(owner);
        if (mine == null || mine.remove(name) == null) {
            actor.sendMessage(plugin.msg("home-unknown", Placeholder.unparsed("name", name)));
            return false;
        }
        dirty();
        actor.sendMessage(plugin.msg("home-deleted", Placeholder.unparsed("name", name)));
        return true;
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    /**
     * Catches the next chat message from someone who shift-clicked a home. LOWEST priority so
     * the name never reaches the chat plugins and gets broadcast to the server.
     *
     * Fires async; everything it touches is a concurrent map or a message send, so that's fine.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Pending pending = awaitingName.get(p.getUniqueId());
        if (pending == null) return;
        if (System.currentTimeMillis() > pending.expires()) {
            awaitingName.remove(p.getUniqueId());
            return; // stale prompt: let them just talk
        }
        e.setCancelled(true);
        awaitingName.remove(p.getUniqueId());

        String typed = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (typed.equalsIgnoreCase("cancel")) {
            p.sendMessage(plugin.msg("home-rename-cancelled"));
            return;
        }
        renameHome(p, pending.owner(), pending.home(), typed);
    }

    private Location locate(UUID owner, String name) {
        if (HUB.equals(name)) return communityHub;
        if (BED.equals(name)) {
            Player online = plugin.getServer().getPlayer(owner);
            return online == null ? null : bedOf(online);
        }
        return mine(owner).get(name);
    }

    // ---------------------------------------------------------------- admin

    private void adminOpen(Player p, String targetName) {
        if (!may(p, "kremlin.home.others", "combatprev.home.others", "essentials.home.others")) {
            p.sendMessage(plugin.msg("home-no-permission"));
            return;
        }
        if (targetName == null) {
            p.sendMessage(plugin.msg("home-usage-admin"));
            return;
        }
        UUID target = findPlayer(targetName);
        if (target == null) {
            p.sendMessage(plugin.msg("home-unknown-player", Placeholder.unparsed("player", targetName)));
            return;
        }
        openGui(p, target, true);
    }

    /** Everyone we have homes stored for. What /adminhome can actually be pointed at. */
    Collection<String> knownNames() {
        return names.values();
    }

    /** One player's home names, plus the two shortcuts that share the namespace. */
    List<String> homeNamesOf(UUID owner) {
        List<String> out = new ArrayList<>(mine(owner).keySet());
        out.add(BED);
        if (communityHub != null) out.add(HUB);
        return out;
    }

    /** Online (nickname or real name) first, then anyone we have homes stored for, then Mojang cache. */
    UUID findPlayer(String query) {
        Player online = plugin.getTpa().resolve(query);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(query)) return e.getKey();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(query);
        return cached == null ? null : cached.getUniqueId();
    }

    // ---------------------------------------------------------------- essentials import

    /** What an import did, or would do. */
    record ImportResult(int files, int players, int imported, int skippedExisting, int skippedWorld) {
        String summary() {
            return files + " userdata file(s), " + players + " player(s) with homes, "
                    + imported + " home(s) to import, " + skippedExisting + " already present, "
                    + skippedWorld + " skipped (world not loaded)";
        }
    }

    /**
     * Pull homes out of Essentials' own userdata files.
     *
     * Deliberately additive: a home we already have always wins, so running this twice changes
     * nothing the second time and it can never clobber something a player set here. The
     * per-player limit is NOT enforced during import -- someone with eight Essentials homes
     * keeps all eight, they just can't add a ninth.
     */
    ImportResult importEssentials(boolean apply) {
        File userdata = new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata");
        plugin.getLogger().info("Home import scanning " + userdata.getAbsolutePath()
                + " (exists=" + userdata.isDirectory() + ")");
        File[] files = userdata.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            plugin.getLogger().warning("No Essentials userdata directory there -- nothing to import.");
            return new ImportResult(0, 0, 0, 0, 0);
        }

        int players = 0, imported = 0, existing = 0, badWorld = 0;
        for (File f : files) {
            UUID id = parseUuid(f.getName().substring(0, f.getName().length() - 4));
            if (id == null) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection section = y.getConfigurationSection("homes");
            if (section == null || section.getKeys(false).isEmpty()) continue;
            players++;

            String lastName = y.getString("last-account-name", y.getString("lastAccountName"));
            Map<String, Location> mine = homes.computeIfAbsent(id,
                    k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

            for (String name : section.getKeys(false)) {
                ConfigurationSection h = section.getConfigurationSection(name);
                if (h == null) continue;
                String key = clean(name);
                if (key.isEmpty()) continue;
                if (mine.containsKey(key)) {
                    existing++;
                    continue;
                }
                World world = resolveWorld(h);
                if (world == null) {
                    if (badWorld < 5) {
                        plugin.getLogger().warning("Skipping home '" + name + "' of " + id
                                + ": no loadable world. Keys present: " + h.getKeys(false)
                                + " world=" + h.getString("world") + " world-name=" + h.getString("world-name"));
                    }
                    badWorld++;
                    continue;
                }
                Location loc = new Location(world, h.getDouble("x"), h.getDouble("y"), h.getDouble("z"),
                        (float) h.getDouble("yaw"), (float) h.getDouble("pitch"));
                imported++;
                if (apply) mine.put(key, loc);
            }
            if (apply && lastName != null) names.put(id, lastName);
            if (mine.isEmpty()) homes.remove(id);
        }
        if (apply) save();
        ImportResult result = new ImportResult(files.length, players, imported, existing, badWorld);
        plugin.getLogger().info((apply ? "Home import applied: " : "Home import dry run: ") + result.summary());
        return result;
    }

    /** Essentials writes a world UUID and a name, and the key spelling has changed over time. */
    private static World resolveWorld(ConfigurationSection h) {
        for (String key : new String[]{"world", "worldName", "world-name"}) {
            String value = h.getString(key);
            if (value == null || value.isEmpty()) continue;
            UUID id = parseUuid(value);
            World world = id != null ? Bukkit.getWorld(id) : Bukkit.getWorld(value);
            if (world != null) return world;
        }
        return null;
    }

    // ---------------------------------------------------------------- items

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

    String describe() {
        return "players " + homes.size() + " | default limit " + defaultMax
                + " | community hub " + (communityHub == null ? "unset" : "set");
    }
}
