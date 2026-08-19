package me.lawsonhart.kremlin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Our own /tpa, replacing Essentials' entirely.
 *
 * Every tpa command is intercepted in PlayerCommandPreprocessEvent at LOWEST priority and
 * cancelled, so it never reaches Essentials no matter which plugin owns the label. That single
 * choke point also closes the /essentials:tpa hole for free -- rootCommand() strips the
 * namespace before matching, so the prefixed form lands here too.
 *
 * Owning the teleport means the combat check and the mutual stand-still warmup happen inline
 * rather than by guessing at somebody else's teleport event.
 */
public final class Tpa implements Listener {

    /** A pending request. {@code here} = /tpahere, i.e. the target is the one who moves. */
    record Request(UUID from, UUID to, boolean here, long expires) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();

    static final Set<String> ASK = Set.of("tpa", "tpask", "call");
    static final Set<String> ASK_HERE = Set.of("tpahere", "tpaskhere");
    static final Set<String> ACCEPT = Set.of("tpaccept", "tpyes");
    static final Set<String> DENY = Set.of("tpdeny", "tpno");
    static final Set<String> CANCEL = Set.of("tpacancel", "tpcancel");

    private static final int SIZE = 54;
    private static final int PLAYERS_START = 9;
    private static final int PLAYERS_END = 44;
    private static final int PER_PAGE = PLAYERS_END - PLAYERS_START + 1;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;

    private final Combat plugin;
    /** target -> (requester -> request) */
    private final Map<UUID, Map<UUID, Request>> incoming = new ConcurrentHashMap<>();
    /** uuid -> epoch millis their tpa cooldown ends */
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    private long requestMillis;
    private long cooldownMillis;

    Tpa(Combat plugin) {
        this.plugin = plugin;
    }

    void load() {
        requestMillis = plugin.getConfig().getLong("tpa.request-seconds", 120) * 1000L;
        cooldownMillis = plugin.getConfig().getLong("tpa.cooldown-seconds", 30) * 1000L;
    }

    int pendingCount() {
        return incoming.values().stream().mapToInt(Map::size).sum();
    }

    void forget(UUID id) {
        incoming.remove(id);
        cooldown.remove(id);
        incoming.values().forEach(m -> m.remove(id));
    }

    // ---------------------------------------------------------------- command capture

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String cmd = Combat.rootCommand(e.getMessage());
        boolean ours = ASK.contains(cmd) || ASK_HERE.contains(cmd) || ACCEPT.contains(cmd)
                || DENY.contains(cmd) || CANCEL.contains(cmd);
        if (!ours) return;

        // Cancel before anything else can run it -- this is what overrides Essentials.
        e.setCancelled(true);

        String[] parts = e.getMessage().trim().split("\\s+");
        String arg = parts.length > 1 ? parts[1] : null;
        Player p = e.getPlayer();

        if (ASK.contains(cmd) || ASK_HERE.contains(cmd)) {
            boolean here = ASK_HERE.contains(cmd);
            if (arg == null) openGui(p, 0);
            else send(p, arg, here);
        } else if (ACCEPT.contains(cmd)) {
            respond(p, arg, true);
        } else if (DENY.contains(cmd)) {
            respond(p, arg, false);
        } else {
            cancelOutgoing(p, arg);
        }
    }

    // ---------------------------------------------------------------- requests

    private long cooldownLeft(UUID id) {
        Long until = cooldown.get(id);
        if (until == null) return 0L;
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            cooldown.remove(id);
            return 0L;
        }
        return left;
    }

    /** Drop expired entries so nothing shows a request that can no longer be accepted. */
    private Map<UUID, Request> live(UUID target) {
        Map<UUID, Request> mine = incoming.get(target);
        if (mine == null) return Map.of();
        long now = System.currentTimeMillis();
        mine.values().removeIf(r -> r.expires() <= now);
        return mine;
    }

    /**
     * Essentials nicknames land in the player's display name (its default
     * change-displayname: true), so matching that covers nicknames without depending on
     * Essentials at all. Real names are still tried first, so nobody can hide behind a
     * nickname that collides with someone else's actual name.
     */
    Player resolve(String query) {
        List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        List<String> names = online.stream().map(Player::getName).toList();
        List<String> nicks = online.stream().map(Tpa::plainName).toList();
        int i = pickPlayer(query, names, nicks);
        return i < 0 ? null : online.get(i);
    }

    /** Display name with formatting stripped, falling back to the real name. */
    static String plainName(Player p) {
        String shown = PlainTextComponentSerializer.plainText().serialize(p.displayName()).trim();
        return shown.isEmpty() ? p.getName() : shown;
    }

    /** Nickname prefixes ("~Bob") and stray punctuation shouldn't stop a match. */
    static String bare(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    /**
     * Index of the player {@code query} refers to, or -1. Real name beats nickname, exact
     * beats prefix -- so "Bob" always finds the actual Bob even if someone is nicknamed "Bobby".
     */
    static int pickPlayer(String query, List<String> names, List<String> nicks) {
        if (query == null || query.isBlank()) return -1;
        int n = Math.min(names.size(), nicks.size());
        String q = query.toLowerCase(Locale.ROOT);
        String qb = bare(query);
        if (qb.isEmpty()) return -1;

        for (int i = 0; i < n; i++) if (names.get(i).toLowerCase(Locale.ROOT).equals(q)) return i;
        for (int i = 0; i < n; i++) if (bare(nicks.get(i)).equals(qb)) return i;
        for (int i = 0; i < n; i++) if (names.get(i).toLowerCase(Locale.ROOT).startsWith(q)) return i;
        for (int i = 0; i < n; i++) if (bare(nicks.get(i)).startsWith(qb)) return i;
        return -1;
    }

    private void send(Player from, String targetName, boolean here) {
        Player target = resolve(targetName);
        if (target == null) {
            from.sendMessage(plugin.msg("tpa-offline", Placeholder.component("player", Component.text(targetName))));
            return;
        }
        sendTo(from, target, here);
    }

    private void sendTo(Player from, Player target, boolean here) {
        if (target.getUniqueId().equals(from.getUniqueId())) {
            from.sendMessage(plugin.msg("tpa-self"));
            return;
        }
        Component combat = plugin.denyCombat(from);
        if (combat != null) {
            from.sendMessage(combat);
            return;
        }
        long cd = cooldownLeft(from.getUniqueId());
        if (cd > 0) {
            from.sendMessage(plugin.msg("tpa-cooldown", Placeholder.unparsed("time", Combat.secs(cd))));
            return;
        }

        Request request = new Request(from.getUniqueId(), target.getUniqueId(), here,
                System.currentTimeMillis() + requestMillis);
        incoming.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(from.getUniqueId(), request);

        from.sendMessage(plugin.msg(here ? "tpa-sent-here" : "tpa-sent",
                Placeholder.component("player", target.displayName()),
                Placeholder.unparsed("time", String.valueOf(requestMillis / 1000))));
        target.sendMessage(prompt(from.getName(), from.displayName(), here));
    }

    /** The clickable [Accept] / [Deny] line. */
    private Component prompt(String realName, Component shownName, boolean here) {
        Component accept = plugin.msg("tpa-accept-button")
                .clickEvent(ClickEvent.runCommand("/tpaccept " + realName))
                .hoverEvent(HoverEvent.showText(plugin.msg("tpa-accept-hover",
                        Placeholder.component("player", shownName))));
        Component deny = plugin.msg("tpa-deny-button")
                .clickEvent(ClickEvent.runCommand("/tpdeny " + realName))
                .hoverEvent(HoverEvent.showText(plugin.msg("tpa-deny-hover",
                        Placeholder.component("player", shownName))));
        return plugin.msg(here ? "tpa-request-here" : "tpa-request",
                        Placeholder.component("player", shownName),
                        Placeholder.unparsed("time", String.valueOf(requestMillis / 1000)))
                .append(Component.space()).append(accept)
                .append(Component.space()).append(deny);
    }

    private void respond(Player p, String fromName, boolean accept) {
        Map<UUID, Request> mine = live(p.getUniqueId());
        Request request = null;
        if (fromName != null) {
            Player from = resolve(fromName);
            if (from != null) request = mine.get(from.getUniqueId());
        } else {
            // No name given: answer the most recent one still standing.
            request = mine.values().stream().max(Comparator.comparingLong(Request::expires)).orElse(null);
        }
        if (request == null) {
            p.sendMessage(plugin.msg("tpa-none"));
            return;
        }
        mine.remove(request.from());
        Player from = plugin.getServer().getPlayer(request.from());

        if (!accept) {
            p.sendMessage(plugin.msg("tpa-denied-you",
                    Placeholder.component("player", from != null ? from.displayName() : Component.text("them"))));
            if (from != null) {
                from.sendMessage(plugin.msg("tpa-denied-them", Placeholder.component("player", p.displayName())));
            }
            return;
        }
        complete(request, p, from);
    }

    private void cancelOutgoing(Player p, String targetName) {
        UUID me = p.getUniqueId();
        boolean removed = false;
        for (Map.Entry<UUID, Map<UUID, Request>> e : incoming.entrySet()) {
            if (targetName != null) {
                Player t = resolve(targetName);
                if (t == null || !t.getUniqueId().equals(e.getKey())) continue;
            }
            if (e.getValue().remove(me) != null) removed = true;
        }
        p.sendMessage(plugin.msg(removed ? "tpa-cancelled" : "tpa-none"));
    }

    /**
     * Accepted. The mover is whoever the request says moves; the other player is the
     * destination and also has to hold still through the warmup.
     */
    private void complete(Request request, Player accepter, Player requester) {
        if (requester == null) {
            accepter.sendMessage(plugin.msg("tpa-offline", Placeholder.component("player", Component.text("they"))));
            return;
        }
        Player mover = request.here() ? accepter : requester;
        Player destination = request.here() ? requester : accepter;

        for (Player each : List.of(mover, destination)) {
            Component combat = plugin.denyCombat(each);
            if (combat != null) {
                accepter.sendMessage(combat);
                requester.sendMessage(combat);
                return;
            }
        }

        accepter.sendMessage(plugin.msg("tpa-accepted-you", Placeholder.component("player", requester.displayName())));
        requester.sendMessage(plugin.msg("tpa-accepted-them", Placeholder.component("player", accepter.displayName())));
        if (cooldownMillis > 0) cooldown.put(mover.getUniqueId(), System.currentTimeMillis() + cooldownMillis);

        // Folia: read the destination on the region that owns that player, never cross-thread.
        destination.getScheduler().run(plugin.owner(), t -> {
            Location dest = destination.getLocation().clone();
            plugin.startWarmup(mover, dest, destination.getUniqueId(), plugin.warmupFor(mover));
        }, null);
    }

    // ---------------------------------------------------------------- gui

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        int page;
        final Map<Integer, UUID> sendTo = new HashMap<>();
        final Map<Integer, UUID> respondTo = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void openGui(Player p, int page) {
        Menu menu = new Menu();
        menu.page = page;
        Inventory inv = plugin.getServer().createInventory(menu, SIZE, plugin.msg("tpa-gui-title"));
        menu.inv = inv;

        // Row 0: requests waiting on you, newest first.
        List<Request> pending = new ArrayList<>(live(p.getUniqueId()).values());
        pending.sort(Comparator.comparingLong(Request::expires).reversed());
        for (int i = 0; i < Math.min(9, pending.size()); i++) {
            Request r = pending.get(i);
            Player from = plugin.getServer().getPlayer(r.from());
            if (from == null) continue;
            long left = Math.max(0L, r.expires() - System.currentTimeMillis()) / 1000L;
            inv.setItem(i, head(from, from.displayName(), List.of(
                    r.here() ? "<gray>Wants you to teleport to them" : "<gray>Wants to teleport to you",
                    "<gray>Expires in <white>" + left + "s",
                    "",
                    "<green>Left-click to accept",
                    "<red>Right-click to deny")));
            menu.respondTo.put(i, r.from());
        }

        // Rows 1-4: everyone online, paginated.
        List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        online.removeIf(o -> o.getUniqueId().equals(p.getUniqueId()));
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        int pages = pageCount(online.size(), PER_PAGE);
        page = wrapPage(page, pages);
        menu.page = page;

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= online.size()) break;
            Player other = online.get(index);
            int slot = PLAYERS_START + i;
            inv.setItem(slot, head(other, other.displayName(), List.of(
                    "<gray>Left-click: ask to teleport <white>to them",
                    "<gray>Right-click: ask them to come <white>to you")));
            menu.sendTo.put(slot, other.getUniqueId());
        }

        if (pages > 1) {
            inv.setItem(SLOT_PREV, item(Material.ARROW, "<white>Previous page", List.of()));
            inv.setItem(SLOT_NEXT, item(Material.ARROW, "<white>Next page", List.of()));
        }
        inv.setItem(49, item(Material.PAPER, "<gold>Page " + (page + 1) + " / " + pages, List.of(
                "<gray>Online: <white>" + online.size(),
                "<gray>Requests waiting on you: <white>" + pending.size())));

        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        UUID respond = menu.respondTo.get(slot);
        if (respond != null) {
            Player from = plugin.getServer().getPlayer(respond);
            p.closeInventory();
            respond(p, from == null ? null : from.getName(), e.isLeftClick());
            return;
        }
        UUID target = menu.sendTo.get(slot);
        if (target != null) {
            Player other = plugin.getServer().getPlayer(target);
            p.closeInventory();
            if (other == null) p.sendMessage(plugin.msg("tpa-offline", Placeholder.component("player", Component.text("they"))));
            else sendTo(p, other, e.isRightClick());
            return;
        }
        if (slot == SLOT_PREV) openGui(p, menu.page - 1);
        else if (slot == SLOT_NEXT) openGui(p, menu.page + 1);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private ItemStack head(Player owner, Component name, List<String> lore) {
        ItemStack stack = ItemStack.of(Material.PLAYER_HEAD);
        // Online player, so the profile is already resolved -- no blocking lookup.
        stack.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        return decorate(stack, name, lore);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        return decorate(ItemStack.of(material), MM.deserialize(name), lore);
    }

    private static ItemStack decorate(ItemStack stack, Component name, List<String> lore) {
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(l -> MM.deserialize(l).decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return stack;
    }

    /** Exposed so /kremlin can report it. */
    String describe() {
        return "requests " + pendingCount() + " | expiry " + requestMillis / 1000
                + "s | cooldown " + cooldownMillis / 1000 + "s";
    }

    /** Total pages for {@code count} entries, never less than one so an empty list still renders. */
    static int pageCount(int count, int perPage) {
        return Math.max(1, (count + perPage - 1) / perPage);
    }

    /** Wraps a page index into range, so prev on page 0 lands on the last page. */
    static int wrapPage(int page, int pages) {
        return Math.floorMod(page, Math.max(1, pages));
    }
}
