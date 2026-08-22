package me.lawsonhart.kremlin.teleport;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Names;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Our own /tpa, replacing Essentials' entirely.
 *
 * Every label is declared in plugin.yml and dispatched here normally. It used to be intercepted
 * in PlayerCommandPreprocessEvent and cancelled, which was the only way to stop Essentials
 * winning the label; with Essentials gone there is nobody left to race.
 *
 * Owning the teleport means the combat check and the mutual stand-still warmup happen inline
 * rather than by guessing at somebody else's teleport event.
 */
public final class Tpa implements Listener, CommandExecutor, TabCompleter {

    /** A pending request. {@code here} = /tpahere, i.e. the target is the one who moves. */
    record Request(UUID from, UUID to, boolean here, long expires) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final Set<String> ASK = Set.of("tpa", "tpask", "call");
    public static final Set<String> ASK_HERE = Set.of("tpahere", "tpaskhere");
    public static final Set<String> ACCEPT = Set.of("tpaccept", "tpyes");
    public static final Set<String> DENY = Set.of("tpdeny", "tpno");
    public static final Set<String> CANCEL = Set.of("tpacancel", "tpcancel");

    /** users.yml key for the ping on an incoming request. On unless turned off. */
    public static final String SOUND = "tpasound";
    /** users.yml key for auto-accept. Off by default -- opting in is the whole point. */
    public static final String AUTO = "tpaauto";

    private static final int SIZE = 54;
    private static final int PLAYERS_START = 9;
    private static final int PLAYERS_END = 44;
    private static final int PER_PAGE = PLAYERS_END - PLAYERS_START + 1;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_AUTO = 47;

    private final Combat plugin;
    /** target -> (requester -> request) */
    private final Map<UUID, Map<UUID, Request>> incoming = new ConcurrentHashMap<>();
    /** uuid -> epoch millis their tpa cooldown ends */
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    private long requestMillis;
    private long cooldownMillis;

    public Tpa(Combat plugin) {
        this.plugin = plugin;
    }

    public void load() {
        requestMillis = plugin.getConfig().getLong("tpa.request-seconds", 120) * 1000L;
        cooldownMillis = plugin.getConfig().getLong("tpa.cooldown-seconds", 30) * 1000L;
    }

    int pendingCount() {
        return incoming.values().stream().mapToInt(Map::size).sum();
    }

    public void forget(UUID id) {
        incoming.remove(id);
        cooldown.remove(id);
        incoming.values().forEach(m -> m.remove(id));
    }

    // ---------------------------------------------------------------- commands

    /**
     * One executor for every tpa label. {@code label} is normalised through
     * {@link Combat#rootCommand} so a namespaced /kremlin:tpa lands on the same branch.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- there is nobody to teleport."));
            return true;
        }
        String cmd = Combat.rootCommand(label);
        String arg = args.length > 0 ? args[0] : null;

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
        return true;
    }

    /** A teleport request only ever goes to somebody who is on right now. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !(sender instanceof Player p)) return List.of();
        List<String> online = Names.online(plugin.getServer(), List.of());
        online.remove(Names.plainName(p));
        return Names.filter(args[0], online);
    }

    /**
     * /tpaall: one request to everybody online. Returns how many were sent -- anyone who has
     * teleports off, or is already being asked, simply does not get one.
     */
    public int askAll(Player from) {
        int sent = 0;
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.getUniqueId().equals(from.getUniqueId())) continue;
            if (!TpCommands.accepts(plugin.getUsers(), other, from)) continue;
            sendTo(from, other, true);
            sent++;
        }
        return sent;
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

    /**
     * Whether an incoming request goes through without asking.
     *
     * A /tpahere never does, however the toggle reads: that one moves the player who set it, and
     * "yes to anything" must not turn into "anybody can drag me anywhere".
     */
    static boolean autoAccepts(boolean here, boolean autoOn) {
        return autoOn && !here;
    }

    /** Drop expired entries so nothing shows a request that can no longer be accepted. */
    private Map<UUID, Request> live(UUID target) {
        Map<UUID, Request> mine = incoming.get(target);
        if (mine == null) return Map.of();
        long now = System.currentTimeMillis();
        mine.values().removeIf(r -> r.expires() <= now);
        return mine;
    }

    private void send(Player from, String targetName, boolean here) {
        Player target = Names.resolve(plugin.getServer(), targetName);
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
        // The same question /tp asks, so a player who turned teleports off is not still reachable
        // through the request path.
        if (!TpCommands.accepts(plugin.getUsers(), target, from)) {
            from.sendMessage(plugin.msg("tp-refused",
                    Placeholder.component("player", plugin.displayName(target))));
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

        if (autoAccepts(here, plugin.getUsers().flag(target.getUniqueId(), AUTO, false))) {
            complete(new Request(from.getUniqueId(), target.getUniqueId(), false, 0L), target, from);
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
        plugin.ping(target, SOUND, "block.note_block.pling");
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
            Player from = Names.resolve(plugin.getServer(), fromName);
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
                Player t = Names.resolve(plugin.getServer(), targetName);
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
        boolean auto = plugin.getUsers().flag(p.getUniqueId(), AUTO, false);
        inv.setItem(SLOT_AUTO, item(auto ? Material.LIME_DYE : Material.GRAY_DYE,
                (auto ? "<green>" : "<red>") + "Auto-accept: <white>" + (auto ? "on" : "off"),
                List.of("<gray>Accept every request to teleport to you,",
                        "<gray>without being asked first.",
                        "<dark_gray>Requests to send you somewhere still ask.",
                        "",
                        "<yellow>Click to " + (auto ? "turn off" : "turn on"))));
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
        if (slot == SLOT_AUTO) {
            UUID id = p.getUniqueId();
            plugin.getUsers().set(id, AUTO, !plugin.getUsers().flag(id, AUTO, false));
            openGui(p, menu.page);
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
    public String describe() {
        return "requests " + pendingCount() + " | expiry " + requestMillis / 1000
                + "s | cooldown " + cooldownMillis / 1000 + "s";
    }

    /** Total pages for {@code count} entries, never less than one so an empty list still renders. */
    public static int pageCount(int count, int perPage) {
        return Math.max(1, (count + perPage - 1) / perPage);
    }

    /** Wraps a page index into range, so prev on page 0 lands on the last page. */
    public static int wrapPage(int page, int pages) {
        return Math.floorMod(page, Math.max(1, pages));
    }
}
